package autodrill.filler;

import arc.Core;
import arc.math.Mathf;
import arc.math.geom.Point2;
import arc.struct.ObjectIntMap;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Team;
import mindustry.type.Item;
import mindustry.world.Tile;
import mindustry.world.blocks.production.Drill;

import java.util.InputMismatchException;

public class BridgeDrill {
  public static void fill(Tile tile, Drill drill, Direction direction) {
    if (drill.size != 2) throw new InputMismatchException("Drill must have a size of 2");

    int maxTiles = Core.settings.getInt(
      (drill == Blocks.mechanicalDrill ? "mechanical" : "pneumatic") + "-drill-max-tiles");

    Seq < Tile > tiles = Util.getConnectedTiles(tile, maxTiles);
    Util.expandArea(tiles, drill.size / 2);
    placeDrillsAndBridges(tile, tiles, drill, direction);
  }

  private static void placeDrillsAndBridges(Tile source, Seq < Tile > tiles, Drill drill, Direction direction) {
    Team team = Vars.player.team();
    Point2 directionConfig = new Point2(direction.p.x * 3, direction.p.y * 3);

    Seq < Tile > drillTiles = tiles.select(BridgeDrill::isDrillTile);
    Seq < Tile > bridgeTiles = tiles.select(BridgeDrill::isBridgeTile);

    int minOresPerDrill = Core.settings.getInt(
      (drill == Blocks.blastDrill ? "airblast" :
        (drill == Blocks.laserDrill ? "laser" :
          (drill == Blocks.pneumaticDrill ? "pneumatic" : "mechanical"))) +
      "-drill-min-ores");

    int totalOreTilesCount = 0;

    drillTiles.retainAll(t -> {
      ObjectIntMap.Entry < Item > itemAndCount = Util.countOre(t, drill);

      if (itemAndCount == null || itemAndCount.key != source.drop() || itemAndCount.value < minOresPerDrill) {
        return false;
      }

      if (!Util.canPlaceBlock(drill, team, t.x, t.y, 0)) {
        return false;
      }

      Seq < Tile > neighbors = Util.getNearbyTiles(t.x, t.y, drill);
      neighbors.retainAll(BridgeDrill::isBridgeTile);

      for (Tile neighbor: neighbors) {
        if (bridgeTiles.contains(neighbor)) return true;
      }

      neighbors.retainAll(n ->
        Util.canPlaceBlock(Blocks.itemBridge, team, n.x, n.y, 0));

      if (!neighbors.isEmpty()) {
        bridgeTiles.add(neighbors);
        return true;
      }

      return false;
    });

    // Подсчитываем точное количество задействованных клеток руды
    for (Tile dt: drillTiles) {
      ObjectIntMap.Entry < Item > cnt = Util.countOre(dt, drill);
      if (cnt != null && cnt.key == source.drop()) {
        totalOreTilesCount += cnt.value;
      }
    }

    Tile outerMost = bridgeTiles.max(
      t -> direction.p.x == 0 ? t.y * direction.p.y : t.x * direction.p.x);
    if (outerMost == null) return;

    Tile outlet = outerMost.nearby(directionConfig);
    if (outlet == null) return;

    bridgeTiles.add(outlet);
    bridgeTiles.sort(t -> t.dst2(outlet.worldx(), outlet.worldy()));

    Seq < BuildPlan > allPlans = new Seq < > ();

    for (Tile drillTile: drillTiles) {
      BuildPlan plan = new BuildPlan(drillTile.x, drillTile.y, 0, drill);
      if (Util.canPlaceWithoutPlanCollision(plan, team, allPlans)) {
        allPlans.add(plan);
      }
    }

    Seq < BuildPlan > bridgePlans = new Seq < > ();

    for (Tile bridgeTile: bridgeTiles) {
      Tile plannedPartner = bridgeTiles.find(
        t -> t != bridgeTile &&
        Math.abs(t.x - bridgeTile.x) + Math.abs(t.y - bridgeTile.y) == 3);

      Tile existingPartner = findExistingBridgePartner(bridgeTile);
      Tile partner = plannedPartner != null ? plannedPartner : existingPartner;

      if (bridgeTile == outlet) {
        boolean hasIncoming = bridgeTiles.contains(
          t -> t != outlet &&
          Math.abs(t.x - outlet.x) + Math.abs(t.y - outlet.y) == 3);
        boolean hasExistingIncoming = findExistingBridgePartner(outlet) != null;

        if (!hasIncoming && !hasExistingIncoming) continue;

        BuildPlan plan = new BuildPlan(bridgeTile.x, bridgeTile.y, 0, Blocks.itemBridge, new Point2());
        if (Util.canPlaceWithoutPlanCollision(plan, team, allPlans)) {
          bridgePlans.add(plan);
        }
      } else if (partner != null) {
        Point2 config = new Point2(partner.x - bridgeTile.x, partner.y - bridgeTile.y);
        BuildPlan plan = new BuildPlan(bridgeTile.x, bridgeTile.y, 0, Blocks.itemBridge, config);
        if (Util.canPlaceWithoutPlanCollision(plan, team, allPlans)) {
          bridgePlans.add(plan);
        }
      }
    }

    // Фильтрация цепочек мостов
    boolean changed = true;
    while (changed) {
      changed = false;
      for (int i = bridgePlans.size - 1; i >= 0; i--) {
        BuildPlan bp = bridgePlans.get(i);
        Point2 cfg = (Point2) bp.config;

        if (cfg.x == 0 && cfg.y == 0) {
          boolean anyPointsHere = false;
          for (int j = 0; j < bridgePlans.size; j++) {
            if (j == i) continue;
            BuildPlan other = bridgePlans.get(j);
            Point2 oCfg = (Point2) other.config;
            if (other.x + oCfg.x == bp.x && other.y + oCfg.y == bp.y) {
              anyPointsHere = true;
              break;
            }
          }
          if (!anyPointsHere) {
            anyPointsHere = hasExistingBridgePointingAt(bp.x, bp.y);
          }
          if (!anyPointsHere) {
            bridgePlans.remove(i);
            changed = true;
          }
          continue;
        }

        int targetX = bp.x + cfg.x;
        int targetY = bp.y + cfg.y;

        boolean targetExists = false;
        for (int j = 0; j < bridgePlans.size; j++) {
          BuildPlan other = bridgePlans.get(j);
          if (other.x == targetX && other.y == targetY) {
            targetExists = true;
            break;
          }
        }
        if (!targetExists) {
          Tile targetTile = Vars.world.tile(targetX, targetY);
          if (targetTile != null && targetTile.build != null && targetTile.block() == Blocks.itemBridge) {
            targetExists = true;
          }
        }
        if (!targetExists) {
          bridgePlans.remove(i);
          changed = true;
        }
      }
    }

    allPlans.addAll(bridgePlans);

    // --- ЛОГИКА ГЕНЕРАЦИИ УГОЛЬНЫХ ГЕНЕРАТОРОВ ДЛЯ МОСТОВЫХ БУРОВ ---
    if (source.drop() == Items.coal && Core.settings.getBool("autodrill-build-generators", false)) {
      float baseSpeed = (drill == Blocks.mechanicalDrill) ? 0.08 f : 0.12 f;
      float totalMiningPerSec = totalOreTilesCount * baseSpeed;

      boolean useSteam = Core.settings.getBool("autodrill-use-steam-generators", false);
      float consumption = useSteam ? 0.66 f : 0.50 f;
      int genCount = Mathf.ceil(totalMiningPerSec / consumption);

      if (genCount > 0) {
        mindustry.world.Block genBlock = useSteam ? Blocks.steamGenerator : Blocks.combustionGenerator;

        // Начинаем строить СЛЕДУЮЩУЮ плитку за выходным мостом (на расстоянии 1 клетки от краев моста)
        int startX = outlet.x + direction.p.x * 1;
        int startY = outlet.y + direction.p.y * 1;

        int dx = direction.p.x;
        int dy = direction.p.y;

        // Вектор сдвига вбок для генератора
        int sideX = (dx == 0) ? 1 : 0;
        int sideY = (dy == 0) ? 1 : 0;

        int curX = startX;
        int curY = startY;

        for (int i = 0; i < genCount; i++) {
          // Маршрутизатор ставится на линию
          BuildPlan routerPlan = new BuildPlan(curX, curY, 0, Blocks.router);
          if (Util.canPlaceWithoutPlanCollision(routerPlan, team, allPlans)) {
            allPlans.add(routerPlan);
          }

          // Генератор ставится вплотную сбоку (размер генераторов 2х2)
          int gX = curX + sideX * 1;
          int gY = curY + sideY * 1;

          BuildPlan genPlan = new BuildPlan(gX, gY, 0, genBlock);
          if (Util.canPlaceWithoutPlanCollision(genPlan, team, allPlans)) {
            allPlans.add(genPlan);
          }

          // Смещаемся по вектору вперед на 1 клетку для следующего маршрутизатора
          curX += dx;
          curY += dy;
        }
      }
    }
    Util.commitPlans(allPlans);
  }
  private static Tile findExistingBridgePartner(Tile bridgeTile) {
    int[][] offsets = {
      {
        3,
        0
      },
      {
        -3,
        0
      },
      {
        0,
        3
      },
      {
        0,
        -3
      }
    };
    for (int[] off: offsets) {
      Tile candidate = Vars.world.tile(bridgeTile.x + off[0], bridgeTile.y + off[1]);
      if (candidate != null && candidate.build != null && candidate.block() == Blocks.itemBridge) {
        return candidate;
      }
    }
    return null;
  }
  private static boolean hasExistingBridgePointingAt(int x, int y) {
    int[][] offsets = {
      {
        3,
        0
      },
      {
        -3,
        0
      },
      {
        0,
        3
      },
      {
        0,
        -3
      }
    };
    for (int[] off: offsets) {
      Tile candidate = Vars.world.tile(x + off[0], y + off[1]);
      if (candidate != null && candidate.build != null && candidate.block() == Blocks.itemBridge) {
        return true;
      }
    }
    return false;
  }
  private static boolean isDrillTile(Tile tile) {
    short x = tile.x;
    short y = tile.y;
    switch (x % 6) {
    case 0:
    case 2:
      if ((y - 1) % 6 == 0) return true;
      break;
    case 1:
      if ((y - 3) % 6 == 0 || (y - 3) % 6 == 2) return true;
      break;
    case 3:
    case 5:
      if ((y - 4) % 6 == 0) return true;
      break;
    case 4:
      if ((y) % 6 == 0 || (y) % 6 == 2) return true;
      break;
    }
    return false;
  }
  private static boolean isBridgeTile(Tile tile) {
    return tile.x % 3 == 0 && tile.y % 3 == 0;
  }
                           }
