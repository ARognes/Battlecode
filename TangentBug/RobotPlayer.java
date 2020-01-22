package MinerPathfinding;
import org.apache.commons.lang3.ObjectUtils.Null;

import battlecode.common.*;

/**
 * @author Austin Rognes
 * 
 * Implemented the Tangent Bug from presentation explanation https://www.cs.cmu.edu/~motionplanning/lecture/Chap2-Bug-Alg_howie.pdf
 * UML diagram: http://4.bp.blogspot.com/-OxVSk7HwsnM/Umrb8H9bvrI/AAAAAAAAADY/7TceTdkxe_E/s1600/4.png
 * Moves towards target, if senses barrier, move towards shortest possible detour,
 * if reach dead end, follow contour until closer than when dead end was found.
 **/

public strictfp class RobotPlayer {
    static RobotController rc;

    // Well hello there traveller! Welcome to static variable hell! How did you get here you may ask?
    // Well, your team member ARognes was creating copious amounts of variables that needed to survive multiple turns,
    // thus he invoked static variable hell through Satanic ritual!

    static int step = 0;        // Used my most Robots
    static MapLocation hqLoc;
    static RobotType rcType;
    static Team rcTeam;
    static MapLocation rcLoc;
    static int rcState = 0;
    static int mapWidth;
    static int mapHeight;
    static int highestBid = 0;
    static int roundBidChecked;
    static int lock;

    static Direction nextFrameSpawnDir; // Used by HQ
    static int minersCreated = 0;

    static MapLocation soupLoc;         // Used by Miner
    static MapLocation soupDepositLoc;
    static MapLocation refineryLoc;
    static int rcTask = 0;
    static boolean[][] nodes;

    static boolean boundaryFollow = false;  // Used by pathfinding
    static Direction moveDir;
    static int consecutiveTurns;
    static boolean hitWall;
    static boolean clockwise;
    static MapLocation startLoc;
    static int dMin;


    /**
     * run() is the method that is called when a robot is instantiated in the Battlecode world.
     * If this method returns, the robot dies!
     **/
    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {

        RobotPlayer.rc = rc;
        rcType = rc.getType();
        rcTeam = rc.getTeam();
        rcLoc = rc.getLocation();
        mapWidth = rc.getMapWidth();
        mapHeight = rc.getMapHeight();
        roundBidChecked = Math.max(1, rc.getRoundNum() - 250);  // Check at most 250 rounds before current round
        lock = GameConstants.GAME_DEFAULT_SEED << ((rc.getTeam() == Team.A) ? 1 : 2);   // Blockchain key only works with team-based lock so this can fight against itself

        // Find and save hq location
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(rc.getCurrentSensorRadiusSquared(), rcTeam);
        for (int i = 0; i < nearbyRobots.length; i++) {         // HQ location must be a neighbor
            if (nearbyRobots[i].getType() == RobotType.HQ) {
                hqLoc = nearbyRobots[i].getLocation();
                break;
            }
        }
        //Direction hqToCenter;
        switch(rcType) {
            case HQ: 
                hqLoc = rcLoc;
                nextFrameSpawnDir = null;

                break;
            case MINER:
                soupLoc = null;
                clockwise = ((rcLoc.x + rcLoc.y) % 2 == 0) ? true : false;   // Randomize somewhat
                nodes = new boolean[mapWidth / 6][mapHeight / 6];

                if(rc.getRoundNum() == 2) rcTask = 1;
                //System.out.println(rc.getRoundNum());
                break;
            case LANDSCAPER:
                soupLoc = null;
                if(hqLoc != null) {   // Search for open locations next to HQ
                    Direction hqToCenter = hqLoc.directionTo(new MapLocation(mapWidth / 2, mapHeight / 2));

                    MapLocation checkLoc = hqLoc.add(hqToCenter.rotateLeft().rotateLeft());
                    if(rc.canSenseLocation(checkLoc) && !rc.isLocationOccupied(checkLoc)) soupLoc = checkLoc;   // LL
                    else {
                        checkLoc = hqLoc.add(hqToCenter.rotateRight().rotateRight());
                        if(rc.canSenseLocation(checkLoc) && !rc.isLocationOccupied(checkLoc)) soupLoc = checkLoc;   // RR
                        else {
                            checkLoc = hqLoc.add(hqToCenter.rotateLeft());
                            if(rc.canSenseLocation(checkLoc) && !rc.isLocationOccupied(checkLoc)) soupLoc = checkLoc;   // L
                            else {
                                checkLoc = hqLoc.add(hqToCenter.rotateRight());
                                if(rc.canSenseLocation(checkLoc) && !rc.isLocationOccupied(checkLoc)) soupLoc = checkLoc;   // R
                                else {
                                    checkLoc = hqLoc.add(hqToCenter);
                                    if(rc.canSenseLocation(checkLoc) && !rc.isLocationOccupied(checkLoc)) soupLoc = checkLoc;   // Center
                                    else {
                                        checkLoc = hqLoc.add(hqToCenter.opposite().rotateRight());
                                        if(rc.canSenseLocation(checkLoc) && !rc.isLocationOccupied(checkLoc)) soupLoc = checkLoc;   // LLL
                                        else {
                                            checkLoc = hqLoc.add(hqToCenter.opposite().rotateLeft());
                                            if(rc.canSenseLocation(checkLoc) && !rc.isLocationOccupied(checkLoc)) soupLoc = checkLoc;   // RRR
                                            else {
                                                checkLoc = hqLoc.add(hqToCenter.opposite());
                                                if(rc.canSenseLocation(checkLoc) && !rc.isLocationOccupied(checkLoc)) soupLoc = checkLoc;   // Back
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                break;
            default:
                
                break;
        }

        //System.out.println(rcType + " created. Found hq at  "  + hqLoc);
        while (true) {
            step++;
            rcLoc = rc.getLocation();

            /*  When rc is created and sits still cooling down, bytecodes should be spent going through Blockchain history to find highest bid of past 250 rounds, or anything else important
                    Cost per round: 18 Bytecodes
                    Cost at start: Max - 2000
            */
            int i = roundBidChecked;
            while(Clock.getBytecodesLeft() > 20 && i < rc.getRoundNum() - 1) {    // Search for highest bid from last checked Blochchain to now, but not all at once
                for(Transaction t : rc.getBlock(i)) {
                    if(t.getCost() > highestBid) {
                        int[] m = t.getMessage();
                        if((m[5] ^ m[2]) != lock) highestBid = t.getCost();  // If message was enemy's, this is their highest bid
                    }
                }
                i++;
            }
            roundBidChecked = i;

            try {
                switch (rcType) {
                    case HQ:                 runHQ();                break;
                    case MINER:              runMiner();             break;
                    case REFINERY:           runRefinery();          break;
                    case VAPORATOR:          runVaporator();         break;
                    case DESIGN_SCHOOL:      runDesignSchool();      break;
                    case FULFILLMENT_CENTER: runFulfillmentCenter(); break;
                    case LANDSCAPER:         runLandscaper();        break;
                    case DELIVERY_DRONE:     runDeliveryDrone();     break;
                    case NET_GUN:            runNetGun();            break;
                }

                // Clock.yield() makes the robot wait until the next turn, then it will perform this loop again
                Clock.yield();

            } catch (Exception e) {
                System.out.println(rcType + " Exception");
                e.printStackTrace();
            }
        }
    }

    /**
     * Simple miner spawn
     */
    static void runHQ() throws GameActionException {
        switch(step) {
            case 1:
                
                // Top priority: Find closest soup. Soup location relative to HQ position
                //MapLocation soupLoc = null;

                int soupDist = 1000;
                for(MapLocation thisSoupLoc : rc.senseNearbySoup()) {
                    if(soupLoc == null || hqLoc.distanceSquaredTo(thisSoupLoc) < soupDist) {   // If this soup is closer than closest soup
                        soupLoc = thisSoupLoc;                                             // This soup is closest soup
                        soupDist = hqLoc.distanceSquaredTo(soupLoc);                // Save distance to closest soup for multiple uses
                        if(soupDist < 3) break;                                     // If radius squared is 2 or 1 then it is adjacent, so stop search
                    }
                }

                soupLoc = soupLoc.translate(-hqLoc.x, -hqLoc.y);

                // Find that soup!
                if(soupLoc != null) {
                    //System.out.println("HQ smells SOUP! " + soupLoc);
                    
                    if(soupLoc.y > 0) {
                        if(soupLoc.y > soupLoc.x) {
                            nextFrameSpawnDir = Direction.NORTHWEST;
                            tryBuildLoose(RobotType.MINER, Direction.NORTH);
                        } else {
                            nextFrameSpawnDir = Direction.NORTHEAST;
                            tryBuildLoose(RobotType.MINER, Direction.EAST);
                        }
                    } else {
                        if(soupLoc.y >= soupLoc.x) {
                            nextFrameSpawnDir = Direction.SOUTHWEST;
                            tryBuildLoose(RobotType.MINER, Direction.WEST);
                        } else {
                            nextFrameSpawnDir = Direction.SOUTHEAST;
                            tryBuildLoose(RobotType.MINER, Direction.SOUTH);
                        }
                    }
                    //rc.setIndicatorDot(new MapLocation(hqLoc.x + soupLoc.x, hqLoc.y + soupLoc.y), 255, 255, 255);
                    break;
                } 
                else {    // Send miners NorthWest and SouthEast so they move diagonally and search 16 new tiles rather than 11
                    nextFrameSpawnDir = Direction.NORTHWEST;
                    tryBuildLoose(RobotType.MINER, Direction.SOUTHEAST);
                }
                minersCreated = 1;

                break;
            case 2: if(nextFrameSpawnDir != null && tryBuildLoose(RobotType.MINER, nextFrameSpawnDir)) minersCreated = 2; break;
            default:
                int teamSoup = rc.getTeamSoup();
                if(minersCreated < 3 && teamSoup >= 70 && tryBuildLoose(RobotType.MINER, nextFrameSpawnDir)) minersCreated++;
                if(minersCreated < 6 && teamSoup >= 70) {
                    int landscapers = 0;
                    for(RobotInfo neighbors : rc.senseNearbyRobots(3)) {
                        if(neighbors.getType() == RobotType.LANDSCAPER) landscapers++;
                    }

                    if(landscapers > 1 && tryBuildLoose(RobotType.MINER, nextFrameSpawnDir)) minersCreated++;
                }

                // Net gun
                RobotInfo [] info = rc.senseNearbyRobots();
                for(int i = 0; i < info.length; i++) {
                    int id = info[i].ID;
                    if(rc.canShootUnit(id) && info[i].getTeam() != rc.getTeam()) {
                        rc.shootUnit(id);
                    }
                }

                break;
        }
    }



    /**
        Miner
            Listen for blockchain,
            Sense environment,
            Build refinery if needed,
            if ready,
                task 1: Build Design School near HQ
                task 2: Go to center of soup deposit
                default:
                    state 2: Go to refinery
                    state 1: Mining soup
                    state 0: Go to soup


     */
    static void runMiner() throws GameActionException {
        int rcRange = rc.getCurrentSensorRadiusSquared();
        int teamSoup = rc.getTeamSoup();

        //#region Blockchain Listening
        int[] listen = getBlockchain();
        if(listen != null) {
            MapLocation newRefinery = new MapLocation(listen[0], listen[1]);    // Listen for new refinery location
            if(newRefinery != null && (refineryLoc == null || newRefinery.distanceSquaredTo(rcLoc) < refineryLoc.distanceSquaredTo(rcLoc) || rcState == 0)) {
                refineryLoc = newRefinery;
                soupLoc = newRefinery;
            }

            // Listen for new soup defosit

            // Listen for node searched
        }
        //#endregion

        //#region Sense Environment
        int soupDist = 1000;
        MapLocation[] soupLocs = rc.senseNearbySoup();
        int cumulativeSoup = 0, centerX = 0, centerY = 0;
        for(MapLocation thisSoupLoc : soupLocs) {           // Search for closest soup and check out soup deposits
            if(soupLoc == null || rcLoc.distanceSquaredTo(thisSoupLoc) < soupDist) {   // If this soup is closer than closest soup
                soupLoc = thisSoupLoc;                                             // This soup is closest soup
                soupDist = rcLoc.distanceSquaredTo(soupLoc);                // Save distance to closest soup for multiple uses
            }

            if(thisSoupLoc.distanceSquaredTo(hqLoc) > 4 && teamSoup >= 400) {
                centerX += thisSoupLoc.x;
                centerY += thisSoupLoc.y;
                if(cumulativeSoup < 400) cumulativeSoup += rc.senseSoup(thisSoupLoc);   // Check if a refinery is needed
            }
        }

        if(soupLocs.length > 0) {
            centerX /= soupLocs.length;
            centerY /= soupLocs.length;
        }

        boolean refineryNearby = false;
        for(RobotInfo nearbyRC : rc.senseNearbyRobots()) {  // Sense nearby robots
            if(nearbyRC.getTeam() == rcTeam) {
                if(nearbyRC.getType() == RobotType.REFINERY) {  // If find friendly refinery, save locally
                    refineryNearby = true;
                    refineryLoc = nearbyRC.getLocation();
                }
            } else if(nearbyRC.getType() == RobotType.DELIVERY_DRONE) { // Enemy drone nearby! Run to HQ!
                rcTask = 0;
                rcState = 2;
                soupLoc = hqLoc;
            }
        }
        //#endregion

        //#region Build Refinery
        if(cumulativeSoup >= 400 && !refineryNearby && centerX != 0 && centerY != 0 && rcTask != 1 && rcLoc.distanceSquaredTo(hqLoc) > 16) {  // If a refinery should be built
            rcTask = 2;
            soupLoc = new MapLocation(centerX, centerY);

            if(teamSoup >= 200 && rcLoc.distanceSquaredTo(soupLoc) < 3 && tryBuildLoose(RobotType.REFINERY, rcLoc.directionTo(soupLoc))) {  // Try build refinery
                rcTask = 0;
                refineryLoc = soupLoc;
                lowPriorityBlockchain(refineryLoc.x, refineryLoc.y, 666, 13, 6969);
            }
        } else if(rcTask == 2) rcTask = 0;
        //#endregion

        if(soupLoc != null && hqLoc.distanceSquaredTo(soupLoc) > 4) soupDepositLoc = soupLoc;

        if(rc.isReady()) {  // Cooldown < 1
            int soupAmount = rc.getSoupCarrying();
            if(soupLoc != null && rcLoc.distanceSquaredTo(soupLoc) < rcRange && rc.senseSoup(soupLoc) < 1 && rcTask == 0) soupLoc = null;    // Where did the soup go?

            if(rcTask == 1 && rc.getTeamSoup() >= 150) {    // Build design school
                
                Direction centerToHQ = new MapLocation(mapWidth / 2, mapHeight / 2).directionTo(hqLoc);
                Direction hqToCenter = centerToHQ.opposite();
                Direction[] checkDirs = new Direction[]{hqToCenter, hqToCenter.rotateLeft(), hqToCenter.rotateRight(), hqToCenter.rotateLeft().rotateLeft(), hqToCenter.rotateRight().rotateRight(), centerToHQ.rotateRight(), centerToHQ.rotateLeft(), centerToHQ};
                MapLocation optimalLoc = null;
                int hqElev = rc.senseElevation(hqLoc);

                for(Direction checkDir : checkDirs) {
                    int elevRef = hqElev;
                    MapLocation checkLoc = hqLoc.add(checkDir);

                    if(rc.canSenseLocation(checkLoc) && !rc.senseFlooding(checkLoc) && Math.abs(rc.senseElevation(checkLoc) - elevRef) < 8) { // If within sense range, not flooded, and surmountable by HQ
                        optimalLoc = checkLoc;
                        elevRef = rc.senseElevation(checkLoc);
                        checkLoc = checkLoc.add(checkDir);
                        if(rc.canSenseLocation(checkLoc) && !rc.senseFlooding(checkLoc) && Math.abs(rc.senseElevation(checkLoc) - elevRef) < 8) { // If within sense range, not flooded, and surmountable by last location
                            optimalLoc = checkLoc;
                            elevRef = rc.senseElevation(checkLoc);
                            checkLoc = checkLoc.add(checkDir);
                            if(rc.canSenseLocation(checkLoc) && !rc.senseFlooding(checkLoc) && Math.abs(rc.senseElevation(checkLoc) - elevRef) < 8) {
                                if(checkDir.dx == 0 || checkDir.dy == 0) {
                                    optimalLoc = checkLoc;
                                    elevRef = rc.senseElevation(checkLoc);
                                    checkLoc = checkLoc.add(checkDir);
                                    if(rc.canSenseLocation(checkLoc) && !rc.senseFlooding(checkLoc) && Math.abs(rc.senseElevation(checkLoc) - elevRef) < 8) optimalLoc = checkLoc;
                                } else optimalLoc = checkLoc;
                            }
                        }
                    }
                }
                if(rcLoc.distanceSquaredTo(optimalLoc) < 3 && tryBuild(RobotType.DESIGN_SCHOOL, rcLoc.directionTo(optimalLoc))) rcTask = 0;
                pathfind(optimalLoc.add(hqLoc.directionTo(optimalLoc)));




            } else if(rcTask == 2) {    // Go to center of soup deposit

                if(soupLoc != null) pathfind(soupLoc);
                else rcTask = 0;

            } else {

                if(rcState == 2) {                                            // Searching for Refinery?

                    MapLocation closestRefinery = refineryLoc;
                    if(closestRefinery == null) closestRefinery = hqLoc;

                    if(rcLoc.distanceSquaredTo(closestRefinery) < 3) {                      // HQ is neighbor?
                        RobotInfo closestR = rc.senseRobotAtLocation(closestRefinery);
                        if(closestR.getType() == RobotType.REFINERY || closestR.getType() == RobotType.HQ) rc.depositSoup(rcLoc.directionTo(closestRefinery), soupAmount);     // Deposit soup
                        else { //Go find new refinery!
                            rcState = 0;
                            rcTask = 0;
                        }

                        if(rc.getSoupCarrying() < 1) {
                            rcState = 0;                // Go collect more soup!
                            soupLoc = soupDepositLoc;   // Set soup to null unless an array of far away soup in 
                            boundaryFollow = false;
                        }
                    }
                    else pathfind(closestRefinery);                      // HQ not neighbor? Keep searching for Refinery
                }
                else if(rcState < 2) {   // Searching for or mining soup?

                    boolean wasMining = (rcState == 1) ? true : false;
                    rcState = 0;                                        // Searching for soup
                    for (Direction dir : Direction.allDirections()) {   // Try to mine soup everywhere
                        if(rc.canMineSoup(dir)) {                       // Mined soup? Stop searching
                            rc.mineSoup(dir);
                            rcState = 1;
                        }
                    }

                    soupAmount = rc.getSoupCarrying();

                    //if(wasMining) {
                        if(soupAmount == RobotType.MINER.soupLimit) {
                            soupLoc = hqLoc;
                            rcState = 2;   // Full? Go search for Refinery
                            if(boundaryFollow) {    // If had to bug in, reverse bug out
                                clockwise = !clockwise;
                                moveDir = moveDir.opposite();
                                consecutiveTurns =0;
                                startLoc = rcLoc;

                                dMin = Math.max(Math.abs(hqLoc.x - rcLoc.x), Math.abs(hqLoc.y - rcLoc.y));
                            }
                        }
                        else if(rcState == 0 && wasMining) soupLoc = null;   // If no more soup, but not full, restart search!
                    //}

                    if(soupLoc != null) pathfind(soupLoc);
                    else {    // Still searching for soup and haven't found any?
                        if(moveDir == null) moveDir = Direction.NORTHEAST;
                        if(rc.canMove(moveDir)) rc.move(moveDir);
                        else if(consecutiveTurns > 0) {
                            moveDir = moveDir.rotateLeft().rotateLeft();
                            consecutiveTurns--;
                            if(consecutiveTurns <= 0) consecutiveTurns = -2;
                        } else {
                            moveDir = moveDir.rotateRight().rotateRight();
                            consecutiveTurns++;
                            if(consecutiveTurns > 0) consecutiveTurns = 3;
                        }

                        if(step % 3 == 0) {
                            if(rc.canMove(moveDir)) rc.move(moveDir);
                            else if(consecutiveTurns > 0) {
                                moveDir = moveDir.rotateLeft();
                                consecutiveTurns--;
                                if(consecutiveTurns <= 0) consecutiveTurns = -2;
                            } else {
                                moveDir = moveDir.rotateRight();
                                consecutiveTurns++;
                                if(consecutiveTurns > 0) consecutiveTurns = 3;
                            }
                        }
                    }

                }

                if(soupAmount < 1 && (rcState == 1 || rcState == 2)) {  // Not carrying soup and not searching for soup? Search for soup!
                    rcState = 0;
                    soupLoc = null;
                }
            }
        }
        if(soupLoc != null) rc.setIndicatorDot(soupLoc, 255, 255, 255);
    }



    /**
     *  Caste ray from rc to end,
     *  if hit barrier, minimal A* the way out,
     *  if stuck in concave shape, bug it out
     */
    static void pathfind(MapLocation endLoc) throws GameActionException {
        if(!rc.isReady()) return;
        int rcRange = rc.getCurrentSensorRadiusSquared();
        int rcElev = rc.senseElevation(rcLoc);

        int startEndX = endLoc.x - rcLoc.x;                 // Vector from start to end
        int startEndY = endLoc.y - rcLoc.y;

        int checkX = 0; // Vector from start to checking tile
        int checkY = 0;
        int checkingIndex; // Index of checking tile in localMap

        Direction optimalDir = Direction.CENTER;
        int optimalCost = (int)Math.max(Math.abs(startEndX), Math.abs(startEndY));  // Min amount of moves needed to get from start to end
        //int dCost = dMin;

        if(boundaryFollow) {    // Follow boundary out of concave shape
            // Check
            //System.out.println(moveDir.toString());
            //rc.setIndicatorDot(rcLoc.add(moveDir), 255, 255, 0);
            
            //if(consecutiveTurns < 3) 
            moveDir = clockwise ? moveDir.rotateRight() : moveDir.rotateLeft();

            MapLocation checkLoc = rcLoc.add(moveDir);
            int i;

            for(i = 0; i<8; i++) {
                if(rc.canSenseLocation(checkLoc) ? (Math.abs(rc.senseElevation(checkLoc) - rcElev) > 3 || rc.senseFlooding(checkLoc) || rc.isLocationOccupied(checkLoc)) : true) { // Hit something
                    moveDir = clockwise ? moveDir.rotateLeft() : moveDir.rotateRight();
                    checkLoc = rcLoc.add(moveDir);
                } else break;
            }

            //System.out.println(i);

            //rc.setIndicatorDot(rcLoc.add(moveDir), 0, 255, 0);
            if(i < 8 && rc.canMove(moveDir)) rc.move(moveDir);

            //System.out.println(">" + moveDir.toString());
            
            if(moveDir.dx != 0 && moveDir.dy != 0) {
                if(i == 0) consecutiveTurns++;
                else consecutiveTurns = 0;

                if(consecutiveTurns < 4) moveDir = clockwise ? moveDir.rotateRight() : moveDir.rotateLeft();
                else {
                    moveDir = clockwise ? moveDir.opposite().rotateLeft() : moveDir.opposite().rotateRight();
                    consecutiveTurns = 1;
                    //clockwise = !clockwise;
                }
            } else consecutiveTurns = 0;

            if(Math.abs(startLoc.x - checkLoc.x) < 2 ^ Math.abs(startLoc.y - checkLoc.y) < 2) {
                //System.out.println("Full Trip! " + startLoc.toString());
                //moveDir = Direction.CENTER;
                boundaryFollow = false;
            }

            if(optimalCost < dMin) boundaryFollow = false;   // Escaped bug!
            else if(!hitWall && (checkLoc.x == 0 || checkLoc.x == mapWidth - 1 || checkLoc.y == 0 || checkLoc.y == mapHeight - 1)) {
                clockwise = !clockwise;    // Hit wall, turn around
                hitWall = true;
            }

        } else {    // Pathfind
            boolean first = true;   // The optimalDirection should be based off the first movement towards endLoc, not the last. In testing they would jump in water sometimes because of this
            while(checkX * checkX + checkY * checkY < rcRange) {
                Direction d = rcLoc.directionTo(new MapLocation(endLoc.x - checkX, endLoc.y - checkY));
                //System.out.println("DIR " + d.toString() + ", " + endLoc.toString());
                checkX += d.dx;
                checkY += d.dy;
                MapLocation checkLoc = rcLoc.translate(checkX, checkY);

                //if(checkX * checkX + checkY * checkY > rcRange) break;    // Ray made it out of range

                //rc.setIndicatorDot(checkLoc, 0, 255, 0);
                if(rcLoc.x + checkX == endLoc.x && rcLoc.y + checkY == endLoc.y) {  // Ray hit endLoc
                    optimalDir = rcLoc.directionTo(checkLoc);    // Simply point in direction of end, no cost calculation needed
                    break;
                }

                //System.out.println("Test " + d.dx + ", " + d.dy); // DEBUG

                checkingIndex = 54 + 11 * checkX - checkY;     // Convert tile coordinates relative to rcLoc to checkingIndex of sensor
                if(checkingIndex > 92) {
                    if(checkingIndex < 103) checkingIndex--;
                    else checkingIndex -= 4;
                } else if(checkingIndex < 16) {
                    if(checkingIndex > 6) checkingIndex++;
                    else checkingIndex += 4; 
                }

                //rc.setIndicatorDot(new MapLocation(rcLoc.x + checkX, rcLoc.y + checkY), 0, 255 - i * 64, 0);    // DEBUG
                //System.out.println(checkLoc.toString());
                if(rc.canSenseLocation(checkLoc) ? (Math.abs(rc.senseElevation(checkLoc) - rcElev) > 3 || rc.senseFlooding(checkLoc) || rc.isLocationOccupied(checkLoc)) : false) {  // Hit something

                    //dCost = Math.max(Math.abs(checkX - d.dx), Math.abs(checkY - d.dy)) + Math.max(Math.abs(startEndX - (checkX - d.dx)), Math.abs(startEndY - (checkY  - d.dy))); // Get optimal cost of tile before collision
                    if(checkX - d.dx + checkY - d.dy != 0) optimalCost = Math.max(Math.abs(checkX - d.dx), Math.abs(checkY - d.dy)) + Math.max(Math.abs(startEndX - (checkX - d.dx)), Math.abs(startEndY - (checkY  - d.dy))); // Get optimal cost of tile before collision
                    startEndX = checkX; // Vector from start to collided tile
                    startEndY = checkY;

                    int startEndXRight = checkX;
                    int startEndYRight = checkY;
                    
                    //rc.setIndicatorDot(new MapLocation(rcLoc.x + checkX, rcLoc.y + checkY), 0, 0, 255); // DEBUG

                    int leftDone = 0;   // Finish search if ray castes to end of sensor range
                    int rightDone = 0;
                    //System.out.println("HIT!");
    
                    do {    // Check tiles in rays shot left, right, left, right ect.

                        if(leftDone < 6) { 
                            
                            if(startEndY > 0) {     // Vector from start to open space to the LEFT of collided tile
                                if(startEndX >= 0) startEndX--;
                                else startEndY--;
                            } else if(startEndY < 0) {
                                if(startEndX > 0) startEndY++;
                                else startEndX++;
                            } else if(startEndX > 0) startEndY++;
                            else startEndY--;

                            //System.out.println("Left: " + leftDone + " (" + startEndX + ", " + startEndY + ")");

                            checkX = 0; // Vector used to change update
                            checkY = 0;

                            //System.out.println("Left turn: " + startEndX + " " + startEndY);      // DEBUG

                            while(checkX * checkX + checkY * checkY < rcRange) {    // Check tiles in a ray until a barrier is hit, end is hit, or ray escapes range
                                //System.out.println("Left While: " + leftDone);
                                d = rcLoc.directionTo(new MapLocation(rcLoc.x + startEndX, rcLoc.y + startEndY));
                                checkX += d.dx;
                                checkY += d.dy;
                                checkLoc = rcLoc.translate(checkX, checkY);

                                //System.out.println(d.toString() + " - " + checkLoc.toString());

                                checkingIndex = 54 + 11 * checkX - checkY; // Convert tile coordinates relative to start to checkingIndex
                                if(checkingIndex > 92) {
                                    if(checkingIndex < 103) checkingIndex--;
                                    else checkingIndex -= 4;
                                } else if(checkingIndex < 16) {
                                    if(checkingIndex > 6) checkingIndex++;
                                    else checkingIndex += 4;
                                }

                                //rc.setIndicatorDot(checkLoc, 100, 0, 100);

                                if(rc.canSenseLocation(checkLoc) ? (Math.abs(rc.senseElevation(checkLoc) - rcElev) > 3 || rc.senseFlooding(checkLoc) || rc.isLocationOccupied(checkLoc)) : false) {  // Hit something
                                    //System.out.println("Left While HIT: " + (Math.abs(rc.senseElevation(checkLoc) - rcElev)) + ", " + rc.senseFlooding(checkLoc) + ", " + rc.isLocationOccupied(checkLoc));
                                    if(checkX - d.dx + checkY - d.dy != 0) {
                                        //System.out.println("PLEASE" + (checkX - d.dx + checkY - d.dy));
                                        int checkCost = Math.max(Math.abs(checkX - d.dx), Math.abs(checkY - d.dy)) + Math.max(Math.abs(endLoc.x - rcLoc.x - (checkX - d.dx)), Math.abs(endLoc.y - rcLoc.y - (checkY - d.dy)));
                                        //System.out.println("$$: " + checkCost);
                                        if(checkCost < optimalCost) {
                                            optimalCost = checkCost;
                                            optimalDir = rcLoc.directionTo(new MapLocation(checkX - d.dx + rcLoc.x, checkY - d.dy + rcLoc.y));
                                        }
                                    }

                                    break;
                                }

                                if(checkX == startEndX && checkY == startEndY) {    // Found offset end
                                    //System.out.println("Left While END!: " + leftDone);
                                    int checkCost = Math.max(Math.abs(checkX), Math.abs(checkY)) + Math.max(Math.abs(endLoc.x - rcLoc.x - checkX), Math.abs(endLoc.y - rcLoc.y - checkY));
                                    //System.out.println("$$$: " + checkCost);
                                    if(checkCost <= optimalCost) {
                                        optimalCost = checkCost;
                                        optimalDir = rcLoc.directionTo(new MapLocation(checkX + rcLoc.x, checkY + rcLoc.y));
                                    }
                                    leftDone = 10;
                                    break;
                                }
                                //System.out.println("SE: (" + startEndX + ", " + startEndY + ") | (" + checkX + ", " + checkY + ")" + "Atan: " + Math.atan2(startEndY - endLoc.y, startEndX - endLoc.x));
                            }
                            leftDone++;
                        }
                    
                        if(rightDone < 6) { 
                            //System.out.println("Right: " + rightDone);
                            if(startEndYRight > 0) {     // Vector from start to open space to the RIGHT of collided tile
                                if(startEndXRight > 0) startEndYRight--;
                                else startEndXRight++;
                            } else if(startEndYRight < 0) {
                                if(startEndXRight >= 0) startEndXRight--;
                                else startEndYRight++;
                            } else if(startEndXRight > 0) startEndYRight--;
                            else startEndYRight++;

                            checkX = 0; // Vector used to change update
                            checkY = 0;

                            while(checkX * checkX + checkY * checkY < rcRange) {
                                //System.out.println("Right while: " + rightDone);
                                d = rcLoc.directionTo(new MapLocation(rcLoc.x + startEndXRight, rcLoc.y + startEndYRight));
                                checkX += d.dx;
                                checkY += d.dy;
                                checkLoc = rcLoc.translate(checkX, checkY);

                                //System.out.println(d.toString() + " - " + checkLoc.toString());

                                checkingIndex = 54 + 11 * checkX - checkY; // Convert tile coordinates relative to start to checkingIndex
                                if(checkingIndex > 92) {
                                    if(checkingIndex < 103) checkingIndex--;
                                    else checkingIndex -= 4;
                                } else if(checkingIndex < 16) {
                                    if(checkingIndex > 6) checkingIndex++;
                                    else checkingIndex += 4;
                                }

                                //rc.setIndicatorDot(checkLoc, 255, 0, 255);
                                //else rc.setIndicatorDot(new MapLocation(rcLoc.x + checkX, rcLoc.y + checkY), 255 - 64 * j, 0, 0);

                                if(rc.canSenseLocation(checkLoc) ? (Math.abs(rc.senseElevation(checkLoc) - rcElev) > 3 || rc.senseFlooding(checkLoc) || rc.isLocationOccupied(checkLoc)) : false) {  // Hit something
                                    //System.out.println("Right while HIT: " + rightDone);
                                    if(checkX - d.dx + checkY - d.dy != 0) {
                                        int checkCost = Math.max(Math.abs(checkX - d.dx), Math.abs(checkY - d.dy)) + Math.max(Math.abs(endLoc.x - rcLoc.x - (checkX - d.dx)), Math.abs(endLoc.y - rcLoc.y - (checkY - d.dy)));
                                        //System.out.println("$$: " + checkCost);
                                        if(checkCost < optimalCost) {
                                            optimalCost = checkCost;
                                            optimalDir = rcLoc.directionTo(new MapLocation(checkX - d.dx + rcLoc.x, checkY - d.dy + rcLoc.y));
                                        }
                                    }
                                    break;
                                }

                                if(checkX == startEndXRight && checkY == startEndYRight) {
                                    //System.out.println("Right while END!: " + rightDone);
                                    int checkCost = Math.max(Math.abs(checkX), Math.abs(checkY)) + Math.max(Math.abs(endLoc.x - rcLoc.x - checkX), Math.abs(endLoc.y - rcLoc.y - checkY));
                                    //System.out.println("$$$: " + checkCost);
                                    if(checkCost <= optimalCost) {
                                        optimalCost = checkCost;
                                        optimalDir = rcLoc.directionTo(new MapLocation(checkX + rcLoc.x, checkY + rcLoc.y));
                                    }
                                    rightDone = 10;
                                    break;
                                }
                            }
                            rightDone++;
                        }
                        
                    } while(leftDone < 6 || rightDone < 6);
                    break;
                }

                if(first) {
                    first = false;
                    optimalDir = d;
                }
            }

            if(optimalDir == Direction.CENTER) {  // If optimal path costs more than the last optimal path, rc must be stuck
                boundaryFollow = true;
                startLoc = rcLoc;
                consecutiveTurns = 0;
                hitWall = false;
                moveDir = rcLoc.directionTo(endLoc);
                moveDir = clockwise ? moveDir.rotateLeft().rotateLeft() : moveDir.rotateRight().rotateRight();    // Arbitrarily search clockwise, optimize this later
                startLoc = rcLoc;
                dMin = optimalCost;
            }

            if(rc.canMove(optimalDir)) rc.move(optimalDir);       // Check if can move, just to avoid any possible errors that haven't been accounted for
            startLoc = rcLoc.add(optimalDir);
        }
    }





    static void runRefinery() throws GameActionException {

    }

    static void runVaporator() throws GameActionException {

    }

    static void runDesignSchool() throws GameActionException {

        if(hqLoc == null) {
            RobotInfo[] nearbyRobots = rc.senseNearbyRobots(rc.getCurrentSensorRadiusSquared(), rcTeam);
            for (int i = 0; i < nearbyRobots.length; i++) {         // HQ location must be a neighbor
                if (nearbyRobots[i].getType() == RobotType.HQ) {
                    hqLoc = nearbyRobots[i].getLocation();
                    break;
                }
            }
        }

        if(hqLoc != null) { // Don't do anything without hqLoc
            if(rc.getTeamSoup() >= 600) hitWall = true;
            if(rc.getTeamSoup() >= 150 && (minersCreated < 2 || (hitWall && minersCreated < 16)) && tryBuildLoose(RobotType.LANDSCAPER, rcLoc.directionTo(hqLoc))) minersCreated++;
        }
    }

    static void runFulfillmentCenter() throws GameActionException {
        for (Direction dir : Direction.allDirections())
            if(tryBuild(RobotType.DELIVERY_DRONE, dir)) break;
    }

    /**
     * Defensive strategy, turtle HQ
     * 
     */
    static void runLandscaper() throws GameActionException {

        if(rc.getDirtCarrying() > 0) {
            RobotInfo[] nearbyEnemies = rc.senseNearbyRobots(rc.getCurrentSensorRadiusSquared(), rcTeam.opponent());
            MapLocation enemyLoc;
            for(RobotInfo enemy : nearbyEnemies) {
                RobotType enemyType = enemy.getType();
                if(enemyType == RobotType.DESIGN_SCHOOL ||  enemyType == RobotType.FULFILLMENT_CENTER || enemyType == RobotType.NET_GUN || enemyType == RobotType.REFINERY || enemyType == RobotType.VAPORATOR) {
                    enemyLoc = enemy.getLocation();
                    if(rcLoc.distanceSquaredTo(enemyLoc) < 3) {
                        Direction rcToEnemy = rcLoc.directionTo(enemyLoc);
                        if(rc.canDepositDirt(rcToEnemy)) rc.depositDirt(rcToEnemy);
                    } else pathfind(enemyLoc);
                }
            }
        }

        if(hqLoc == null) {     // If didn't find HQ at start due to pollution, find it!
            RobotInfo[] nearbyRobots = rc.senseNearbyRobots(rc.getCurrentSensorRadiusSquared(), rcTeam);
            for (int i = 0; i < nearbyRobots.length; i++) {         // HQ location must be a neighbor
                if (nearbyRobots[i].getType() == RobotType.HQ) {
                    hqLoc = nearbyRobots[i].getLocation();
                    break;
                }
            }

            Direction franticSearch = rcLoc.directionTo(new MapLocation(mapWidth/2, mapHeight/2));
            if(rcLoc.distanceSquaredTo(new MapLocation(mapWidth/2, mapHeight/2)) > mapWidth * mapHeight / 5) franticSearch = franticSearch.opposite();
            if(rc.canMove(franticSearch)) rc.move(franticSearch);
        }


        if(hqLoc != null) {
            int distToHQ = rcLoc.distanceSquaredTo(hqLoc);
            // Save HQ
            if(rc.isReady()) {
                Direction checkHQ = rcLoc.directionTo(hqLoc);
                if(rc.canDigDirt(checkHQ) && distToHQ < 3) rc.digDirt(checkHQ);
            }


            if(soupLoc == null || (soupLoc != null && (soupLoc.x != rcLoc.x && soupLoc.y != rcLoc.y)) /* || (not in soupLoc && soupLoc taken) */) {   // If didn't find open location next to HQ at start, or did and isn't in position yet
                int rcElev = rc.senseElevation(rcLoc);
                soupLoc = hqLoc;
                Direction hqToCenter = hqLoc.directionTo(new MapLocation(mapWidth / 2, mapHeight / 2));     // Search for open locations next to HQ by priority
                MapLocation checkLoc = hqLoc.add(hqToCenter.rotateLeft().rotateLeft());
                if(rc.canSenseLocation(checkLoc) && !rc.isLocationOccupied(checkLoc) && Math.abs(rc.senseElevation(checkLoc) - rcElev) < 3) soupLoc = checkLoc;   // LL
                else {
                    checkLoc = hqLoc.add(hqToCenter.rotateRight().rotateRight());
                    if(rc.canSenseLocation(checkLoc) && !rc.isLocationOccupied(checkLoc) && Math.abs(rc.senseElevation(checkLoc) - rcElev) < 3) soupLoc = checkLoc;   // RR
                    else {
                        checkLoc = hqLoc.add(hqToCenter.rotateLeft());
                        if(rc.canSenseLocation(checkLoc) && !rc.isLocationOccupied(checkLoc) && Math.abs(rc.senseElevation(checkLoc) - rcElev) < 3) soupLoc = checkLoc;   // L
                        else {
                            checkLoc = hqLoc.add(hqToCenter.rotateRight());
                            if(rc.canSenseLocation(checkLoc) && !rc.isLocationOccupied(checkLoc) && Math.abs(rc.senseElevation(checkLoc) - rcElev) < 3) soupLoc = checkLoc;   // R
                            else {
                                checkLoc = hqLoc.add(hqToCenter);
                                if(rc.canSenseLocation(checkLoc) && !rc.isLocationOccupied(checkLoc) && Math.abs(rc.senseElevation(checkLoc) - rcElev) < 3) soupLoc = checkLoc;   // Center
                                else {
                                    checkLoc = hqLoc.add(hqToCenter.opposite().rotateRight());
                                    if(rc.canSenseLocation(checkLoc) && !rc.isLocationOccupied(checkLoc) && Math.abs(rc.senseElevation(checkLoc) - rcElev) < 3) soupLoc = checkLoc;   // LLL
                                    else {
                                        checkLoc = hqLoc.add(hqToCenter.opposite().rotateLeft());
                                        if(rc.canSenseLocation(checkLoc) && !rc.isLocationOccupied(checkLoc) && Math.abs(rc.senseElevation(checkLoc) - rcElev) < 3) soupLoc = checkLoc;   // RRR
                                        else {
                                            checkLoc = hqLoc.add(hqToCenter.opposite());
                                            if(rc.canSenseLocation(checkLoc) && !rc.isLocationOccupied(checkLoc) && Math.abs(rc.senseElevation(checkLoc) - rcElev) < 3) soupLoc = checkLoc;   // Back
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            if(soupLoc != null) {
                if(soupLoc != rcLoc && step > 60) soupLoc = soupLoc.add(soupLoc.directionTo(rcLoc));
                //rc.setIndicatorDot(soupLoc, 255, 0, 0);

                //int distToHQ = rcLoc.distanceSquaredTo(hqLoc);
                
                if(rcLoc.x == soupLoc.x && rcLoc.y == soupLoc.y) {
                    if(rc.isReady()) {
                        //Direction checkHQ = rcLoc.directionTo(hqLoc);
                        //if(rc.canDigDirt(checkHQ) && distToHQ < 3) rc.digDirt(checkHQ);
                        //else {
                            
                            if(rc.getDirtCarrying() > 0) {
                                rc.depositDirt(Direction.CENTER);

                                /*int lowestElevation = rc.senseElevation(rcLoc);
                                Direction lowestElevationDir = Direction.CENTER;
                                
                                Direction checkDir = rcLoc.directionTo(hqLoc).rotateLeft();
                                int checkElev = rc.senseElevation(rcLoc.add(checkDir));
                                if(checkElev < lowestElevation && rc.isLocationOccupied(rcLoc.add(checkDir)) && rc.senseRobotAtLocation(rcLoc.add(checkDir)).getType() == RobotType.LANDSCAPER) {
                                    lowestElevation = checkElev;
                                    lowestElevationDir = checkDir;
                                }

                                checkDir = rcLoc.directionTo(hqLoc).rotateRight();
                                checkElev = rc.senseElevation(rcLoc.add(checkDir));
                                if(checkElev < lowestElevation && rc.isLocationOccupied(rcLoc.add(checkDir)) && rc.senseRobotAtLocation(rcLoc.add(checkDir)).getType() == RobotType.LANDSCAPER) {
                                    lowestElevation = checkElev;
                                    lowestElevationDir = checkDir;
                                }
                                rc.depositDirt(lowestElevationDir);*/

                                /*int lowestElevation = rc.senseElevation(rcLoc);
                                Direction lowestElevationDir = Direction.CENTER;
                                for(Direction d : Direction.allDirections())
                                    if(hqLoc.distanceSquaredTo(rcLoc.add(d)) < 3 && rc.canDepositDirt(d) && rc.senseElevation(rcLoc.add(d)) < lowestElevation) {
                                        lowestElevation = rc.senseElevation(rcLoc.add(d));
                                        lowestElevationDir = d;
                                    }

                                    rc.depositDirt(lowestElevationDir);*/
                                
                            }
                            else {  // Find diggable site
                                for(Direction d : Direction.allDirections()) {
                                    distToHQ = rcLoc.add(d).distanceSquaredTo(hqLoc);
                                    if(rcLoc.add(d).distanceSquaredTo(hqLoc) == 4 && rc.canDigDirt(d)/* && rc.senseRobotAtLocation(rcLoc.add(d)) == null*/) rc.digDirt(d);
                                }
                            }
                        //}
                    }
                } else {    // Go to assigned location
                    //System.out.println(soupLoc + "B");
                    /*if(rcLoc.distanceSquaredTo(soupLoc) < 3 && rc.isReady()) {  // Can't move to assigned location due to elevation difference
                        int deltaDirt = rc.senseElevation(rcLoc) - rc.senseElevation(soupLoc);  // Difference in dirt from current location to assigned location
                        Direction dirToAssigned = rcLoc.directionTo(soupLoc);

                        if(deltaDirt >= 3) { // Fill rcLoc from assignedLoc
                            if(rc.canDigDirt(Direction.CENTER)) rc.digDirt(Direction.CENTER);
                            else if(rc.getDirtCarrying() > 0) rc.depositDirt(dirToAssigned);
                            
                        } else if(deltaDirt <= -3) { // Fill rcLoc from assigned location
                            if(rc.canDigDirt(dirToAssigned)) rc.digDirt(dirToAssigned);
                            else if(rc.getDirtCarrying() > 0) rc.depositDirt(Direction.CENTER);
                        }
                    }*/

                    pathfind(soupLoc);
                }
            }
        }
    }

    static void runDeliveryDrone() throws GameActionException {

    }

    static void runNetGun() throws GameActionException {

    }

    /**
     * Attempts to build a given robot in a given direction.
     *
     * @param type The type of the robot to build
     * @param dir The intended direction of movement
     * @return true if a move was performed
     * @throws GameActionException
     */
    static boolean tryBuild(RobotType type, Direction dir) throws GameActionException {
        if (rc.isReady() && rc.canBuildRobot(type, dir)) {
            rc.buildRobot(type, dir);
            return true;
        } else return false;
    }

    /**
     * Attempt to build a given robot in any direction with a prioritized direction
     * 
     * @param type The type of robot to build
     * @param dir    The intended direction of movement
     * @return  true if a move was performed
     * @throws GameActionException
     */
    static boolean tryBuildLoose(RobotType type, Direction dir) throws GameActionException {
        if(!rc.isReady()) return false;

        if(rc.canBuildRobot(type, dir)) {   // Try to build robot at prioritized direction
            rc.buildRobot(type, dir);
            return true;
        }
        

        Direction leftRotate = dir.rotateLeft();
        dir = dir.rotateRight();

        if(rc.canBuildRobot(type, leftRotate)) {    // Try 1 CC
            rc.buildRobot(type, leftRotate);
            return true;
        }

        if(rc.canBuildRobot(type, dir)) {           // Try 1 C
            rc.buildRobot(type, dir);
            return true;
        }

        leftRotate = leftRotate.rotateLeft();
        dir = dir.rotateRight();

        if(rc.canBuildRobot(type, leftRotate)) {    // Try 2 CC
            rc.buildRobot(type, leftRotate);
            return true;
        }
        if(rc.canBuildRobot(type, dir)) {           // Try 2 c
            rc.buildRobot(type, dir);
            return true;
        }

        leftRotate = leftRotate.rotateLeft();
        dir = dir.rotateRight();

        if(rc.canBuildRobot(type, leftRotate)) {    // Try 3 CC
            rc.buildRobot(type, leftRotate);
            return true;
        }
        if(rc.canBuildRobot(type, dir)) {           // Try 3 c
            rc.buildRobot(type, dir);
            return true;
        }

        dir = dir.rotateRight();

        if(rc.canBuildRobot(type, dir)) {    // Try 4 C / CC
            rc.buildRobot(type, dir);
            return true;
        }

        return false;
    }

    /**
     * Make sure this message gets sent now
     *      58 Bytecodes
     * 
     * @param message
     */
    static void highPriorityBlockchain(int a, int b, int c, int d, int e) throws GameActionException {
        int key = (rc.getID() + rc.getRoundNum()) ^ 777; // Create key
        int[] message = new int[]{(b ^ 666), (c ^ 666), key, (e ^ 666), (a ^ 666), key ^ lock, (d ^ 666)};   // ^, xor costs 1 bytecode and is an invertible function Hell yes
        if(rc.canSubmitTransaction(message, highestBid + 1)) rc.submitTransaction(message, highestBid + 1);
    }
    
    /**
     * Send this message at minimal cost
     * 
     * @param a-e int messages
     */
    static void lowPriorityBlockchain(int a, int b, int c, int d, int e) throws GameActionException {
        int lowestBid = 1;  // Get lowest bid of last step
        for(Transaction t : rc.getBlock(rc.getRoundNum()-2)) 
            if(t.getCost() < lowestBid) lowestBid = t.getCost();

        int key = (rc.getID() + rc.getRoundNum()) ^ 777; // Create key
        int[] message = new int[]{(b ^ 666), (c ^ 666), key, (e ^ 666), (a ^ 666), key ^ lock, (d ^ 666)};   // ^, xor: an invertible function Hell yes, this line costs 58 bytecode

        if(rc.canSubmitTransaction(message, lowestBid)) rc.submitTransaction(message, lowestBid);
    }

    /**
     * Drawback is that if more than one message is sent on our side it will not be read, however this is extremely rare
     *      117 Bytecodes
     * 
     * @return Decoded Blockchain information, 5 ints
     */
    static int[] getBlockchain() throws GameActionException {
        int lastRound = rc.getRoundNum()-1;
        for(Transaction t : rc.getBlock(lastRound)) {
            int [] message = t.getMessage();
            if((message[5] ^ message[2]) == lock) {
                if((message[2] ^ 777) - lastRound == rc.getID()) continue;  // Don't hear the sound of rc's own voice
                return new int[]{(message[4] ^ 666), (message[0] ^ 666), (message[1] ^ 666), (message[6] ^ 666), (message[3] ^ 666)};
            }
        }
        return null;
    }

    static void logBytecodes() {
        //System.out.println("\nBC Used:" + Clock.getBytecodeNum() + "\tLeft: " + Clock.getBytecodesLeft());
    }
}
