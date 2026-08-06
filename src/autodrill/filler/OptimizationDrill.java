package autodrill.filler;

import arc.Core;
import arc.math.Mathf;
import arc.math.geom.Rect;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Team;
import mindustry.type.Item;
import mindustry.world.Tile;
import mindustry.world.blocks.environment.Floor;
import mindustry.world.blocks.production.Drill;

import static arc.Core.bundle;

public class OptimizationDrill {
    public static void fill(Tile tile, Drill drill) {
        fill(tile, drill, true);
    }

    public static void fill(Tile tile, Drill drill, boolean waterExtractorsAndPowerNodes) {
        Team team = Vars.player.team();

        String drillPrefix = getDrillSettingsPrefix(drill);
        int maxTiles = Core.settings.getInt(drillPrefix + "-drill-max-tiles");

        Seq<Tile> tiles = Util.getConnectedTiles(tile, maxTiles);
        Util.expandArea(tiles, drill.size / 2);

        int minOresPerDrill = Core.settings.getInt(drillPrefix + "-drill-min-ores");

        Floor floor = tile.overlay() != Blocks.air ? tile.overlay() : tile.floor();

        ObjectMap<Tile, ObjectIntMap.Entry<Item>> tilesItemAndCount = new ObjectMap<>();
        for (Tile t : tiles) {
            tilesItemAndCount.put(t, Util.countOre(t, drill));
        }

        tiles.retainAll(t -> {
            ObjectIntMap.Entry<Item> itemAndCount = tilesItemAndCount.get(t);
            if (itemAndCount == null || itemAndCount.key != floor.itemDrop || itemAndCount.value < minOresPerDrill) {
                return false;
            }
            if (!Util.canPlaceBlock(drill, team, t.x, t.y, 0)) {
                return false;
            }
            return true;
        }).sort(t -> {
            ObjectIntMap.Entry<Item> itemAndCount = tilesItemAndCount.get(t);
            return itemAndCount == null ? Integer.MIN_VALUE : -itemAndCount.value;
        });

        Seq<BuildPlan> allPlans = new Seq<>();
        Seq<Tile> selection = new Seq<>();

        int maxTries = Core.settings.getInt(bundle.get("auto-drill.settings.optimization-quality")) * 1000;

        recursiveMaxSearch(tiles, drill, team, tilesItemAndCount, selection, new Seq<>(), 0, new Seq<>(), maxTries, 0);

        for (Tile t : selection) {
            BuildPlan plan = new BuildPlan(t.x, t.y, 0, drill);
            if (Util.canPlaceWithoutPlanCollision(plan, team, allPlans)) {
                allPlans.add(plan);
            }
        }

        // --- ЛОГИКА ГЕНЕРАЦИИ ДЛЯ КРУПНЫХ УГОЛЬНЫХ БУРОВ ---
        if (floor.itemDrop == Items.coal && Core.settings.getBool("autodrill-build-generators", false)) {
            boolean useSteam = Core.settings.getBool("autodrill-use-steam-generators", false);
            mindustry.world.Block genBlock = useSteam ? Blocks.steamGenerator : Blocks.combustionGenerator;
            float consumption = useSteam ? 0.66f : 0.50f;

            float baseSpeed = 0.15f;
            float multiplier = (drill == Blocks.laserDrill) ? 2.56f : 3.24f;

            for (Tile t : selection) {
                ObjectIntMap.Entry<Item> cnt = tilesItemAndCount.get(t);
                if (cnt == null) continue;

                float drillOutput = cnt.value * baseSpeed * multiplier;
                int gensNeeded = Mathf.ceil(drillOutput / consumption);

                int placedGens = 0;
                // Смещения вокруг бура (размер лазерного 3х3, воздушного 4х4)
                int radius = drill.size / 2 + 1;

                int[][] sideOffsets = {{radius, 0}, {-radius, 0}, {0, radius}, {0, -radius}};

                for (int[] offset : sideOffsets) {
                    if (placedGens >= gensNeeded) break;

                    int rX = t.x + offset[0];
                    int rY = t.y + offset[1];

                    // Ставим маршрутизатор в упор к буру
                    BuildPlan routerPlan = new BuildPlan(rX, rY, 0, Blocks.router);
                    if (Util.canPlaceWithoutPlanCollision(routerPlan, team, allPlans)) {
                        allPlans.add(routerPlan);

                        // Пытаемся примостить генератор (2х2) рядом с маршрутизатором
                        int gX = rX + (offset[0] == 0 ? 1 : 0);
                        int gY = rY + (offset[1] == 0 ? 1 : 0);

                        BuildPlan genPlan = new BuildPlan(gX, gY, 0, genBlock);
                        if (Util.canPlaceWithoutPlanCollision(genPlan, team, allPlans)) {
                            allPlans.add(genPlan);
                            placedGens++;
                        }
                    }
                }
            }
        }

        if (waterExtractorsAndPowerNodes && Core.settings.getBool(bundle.get("auto-drill.settings.place-water-extractor-and-power-nodes"))) {
            placeWaterExtractorsAndPowerNodes(selection, drill, team, allPlans);
        }

        Util.commitPlans(allPlans);
    }

    private static int recursiveMaxSearch(
            Seq<Tile> tiles, Drill drill, Team team,
            ObjectMap<Tile, ObjectIntMap.Entry<Item>> tilesItemAndCount,
            Seq<Tile> selection, Seq<Rect> rects, int sum,
            Seq<Integer> triesPerLevel, final int maxTries, final int level) {

        int max = sum;
        Seq<Tile> maxSelection = selection.copy();

        if (triesPerLevel.size < level + 1) {
            triesPerLevel.setSize(level + 1);
            triesPerLevel.set(level, 0);
        }

        for (Tile tile : tiles) {
            Rect rect = Util.getBlockRect(tile, drill);

            if (rects.isEmpty() || rects.find(r -> r.overlaps(rect)) == null) {
                int newSum = sum + tilesItemAndCount.get(tile).value;

                Seq<Tile> newSelection = selection.copy().add(tile);
                Seq<Rect> newRects = rects.copy().add(rect);

                int newMax = recursiveMaxSearch(tiles, drill, team, tilesItemAndCount,
                    newSelection, newRects, newSum, triesPerLevel, maxTries, level + 1);

                if (newMax > max) {
                    max = newMax;
                    maxSelection = newSelection.copy();
                }

                triesPerLevel.set(level, triesPerLevel.get(level) + 1);
                if (triesPerLevel.get(level) >= maxTries / Math.pow(2, level + 1)) break;
            }
        }

        selection.clear();
        selection.addAll(maxSelection);

        return max;
    }

    private static void placeWaterExtractorsAndPowerNodes(
            Seq<Tile> selection, Drill drill, Team team, Seq<BuildPlan> allPlans) {

        for (Tile t : selection) {
            Seq<Tile> nearby = Util.getNearbyTiles(t.x, t.y, drill.size, Blocks.waterExtractor.size);
            for (Tile n : nearby) {
                BuildPlan plan = new BuildPlan(n.x, n.y, 0, Blocks.waterExtractor);
                if (Util.canPlaceWithoutPlanCollision(plan, team, allPlans)) {
                    allPlans.add(plan);
                    break;
                }
            }
        }

        for (Tile t : selection) {
            Seq<Tile> nearby = Util.getNearbyTiles(t.x, t.y, drill.size, Blocks.powerNode.size);
            for (Tile n : nearby) {
                BuildPlan plan = new BuildPlan(n.x, n.y, 0, Blocks.powerNode);
                if (Util.canPlaceWithoutPlanCollision(plan, team, allPlans)) {
                    allPlans.add(plan);
                    break;
                }
            }
        }
    }

    private static String getDrillSettingsPrefix(Drill drill) {
        if (drill == Blocks.mechanicalDrill) return "mechanical";
        if (drill == Blocks.pneumaticDrill)  return "pneumatic";
        if (drill == Blocks.laserDrill)      return "laser";
        if (drill == Blocks.blastDrill)      return "airblast";
        if (drill == Blocks.impactDrill)     return "airblast";
        if (drill == Blocks.eruptionDrill)   return "airblast";
        return "airblast";
    }
        }
