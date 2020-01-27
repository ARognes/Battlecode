package flyingturtlebot;

import java.util.Arrays;

import org.apache.commons.lang3.ObjectUtils.Null;

import battlecode.common.*;

import java.util.Random;

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
    static int rcElev;
    static MapLocation rcLoc;
    static int rcState = 0;
    static int highestBid = 0;
    static int roundBidChecked;
    static int[] lowPriorityBlockchainHold = null;
    static int lock;
    static int mapWidth;
    static int mapHeight;
    static MapLocation mapCenter;

    static Direction nextFrameSpawnDir; // Used by HQ
    static int minersCreated = 0;

    static MapLocation seekLoc;         // Used by Miner
    static MapLocation soupDepositLoc;
    static MapLocation refineryLoc;
    static int rcTask = 0;
    static int[][] soupArray;

    static int refElev;     // Used by Landscaper
    static boolean bool = false;

    static boolean boundaryFollow = false;  // Used by pathfinding
    static Direction moveDir;
    static int consecutiveTurns;
    static boolean hitWall;
    static boolean clockwise;
    static MapLocation startLoc;
    static int dMin;

    static Random random;
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
        rcElev = rc.senseElevation(rcLoc);
        roundBidChecked = Math.max(1, rc.getRoundNum() - 250);  // Check at most 250 rounds before current round
        random = new Random();
        lock = GameConstants.GAME_DEFAULT_SEED ^ ((rcTeam == Team.A) ? 77777777 : 44444444);   // Blockchain key only works with team-based lock so this can fight against itself
        mapWidth = rc.getMapWidth();
        mapHeight = rc.getMapHeight();
        mapCenter = new MapLocation(mapWidth / 2, mapHeight / 2);

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
                seekLoc = null;
                clockwise = ((rcLoc.x + rcLoc.y + rc.getRoundNum()) % 2 == 0) ? true : false;   // Randomize somewhat
                soupArray = new int[mapWidth / 12 + 1][mapHeight / 12 + 1];
                break;
            case LANDSCAPER:
                refElev = rc.senseElevation(rcLoc);
                seekLoc = null;
                if(hqLoc != null && rc.canSenseLocation(rcLoc.add(hqLoc.directionTo(rcLoc)))) refElev = rc.senseElevation(rcLoc.add(hqLoc.directionTo(rcLoc)));
                else refElev = rc.senseElevation(rcLoc);
                break;
            default:
                
                break;
        }

        //System.out.println(rcType + " created. Found hq at  "  + hqLoc);
        while (true) {
            step++;
            rcLoc = rc.getLocation();
            rcElev = rc.senseElevation(rcLoc);

            /*  When rc is created and sits still cooling down, bytecodes should be spent going through Blockchain history to find highest bid of past 250 rounds, or anything else important
                    Cost per round: 18 Bytecodes
                    Cost at start: Max - 2000
            */
            int i = roundBidChecked;
            while(Clock.getBytecodesLeft() > 100 && i < rc.getRoundNum() - 1) {    // Search for highest bid from last checked Blochchain to now, but not all at once
                for(Transaction t : rc.getBlock(i)) {
                    if(t.getCost() > highestBid) {
                        int[] m = t.getMessage();
                        if((m[5] ^ m[2]) != lock) highestBid = t.getCost();  // If message was enemy's, this is their highest bid
                    }
                }
                i++;
            }
            roundBidChecked = i;

            if(lowPriorityBlockchainHold != null) { // Messages sent on delay
                if(lowPriorityBlockchainHold[5] == 0) {
                    lowPriorityBlockchain(lowPriorityBlockchainHold[0], lowPriorityBlockchainHold[1], lowPriorityBlockchainHold[2], lowPriorityBlockchainHold[3], lowPriorityBlockchainHold[4]);
                    lowPriorityBlockchainHold = null;
                } else lowPriorityBlockchainHold[5]--;
            }

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
                System.out.println(rcType + " EXCEPTION");
                e.printStackTrace();
            }
        }
    }

    /**
     * Simple miner spawn
     */
    static void runHQ() throws GameActionException {
        if(step == 1) {
            int soupDist = 1000;
            for(MapLocation thisSoupLoc : rc.senseNearbySoup()) {
                if(seekLoc == null || hqLoc.distanceSquaredTo(thisSoupLoc) < soupDist) {   // If this soup is closer than closest soup
                    seekLoc = thisSoupLoc;                                             // This soup is closest soup
                    soupDist = hqLoc.distanceSquaredTo(seekLoc);                // Save distance to closest soup for multiple uses
                    if(soupDist < 3) break;                                     // If radius squared is 2 or 1 then it is adjacent, so stop search
                }
            }

            if(seekLoc != null) {
                seekLoc = seekLoc.translate(-hqLoc.x, -hqLoc.y);
                System.out.println("Papa bear smells soup!");

                if(seekLoc.y > 0) {
                    if(seekLoc.y > seekLoc.x) {
                        nextFrameSpawnDir = Direction.NORTHWEST;
                        tryBuildLoose(RobotType.MINER, Direction.NORTH);
                    } else {
                        nextFrameSpawnDir = Direction.NORTHEAST;
                        tryBuildLoose(RobotType.MINER, Direction.EAST);
                    }
                } else {
                    if(seekLoc.y >= seekLoc.x) {
                        nextFrameSpawnDir = Direction.SOUTHWEST;
                        tryBuildLoose(RobotType.MINER, Direction.WEST);
                    } else {
                        nextFrameSpawnDir = Direction.SOUTHEAST;
                        tryBuildLoose(RobotType.MINER, Direction.SOUTH);
                    }
                }
            }
            else nextFrameSpawnDir = Direction.NORTHWEST;
            tryBuildLoose(RobotType.MINER, Direction.SOUTHEAST);
            minersCreated = 1;
        } else {
            int teamSoup = rc.getTeamSoup();
            if(minersCreated < 9 && teamSoup >= 70 && tryBuildLoose(RobotType.MINER, nextFrameSpawnDir)) minersCreated++;

            // Net gun @author Augusto Savaris
            RobotInfo [] info = rc.senseNearbyRobots();
            for(RobotInfo thisInfo : info) {
                int id = thisInfo.ID;
                if(rc.canShootUnit(id) && thisInfo.getTeam() != rcTeam) rc.shootUnit(id);
            }
        }
    }



    /**
        Miner
            Sense environment,
            Listen for blockchain,
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

        if(rc.getRoundNum() > 500 && seekLoc == null && rcState == 0) rcTask = 5;

// Sense soup
        int soupDist = 1000;
        MapLocation[] soupLocs = rc.senseNearbySoup();
        int cumulativeSoup = 0, centerX = 0, centerY = 0;
        for(MapLocation thisSoupLoc : soupLocs) {           // Search for closest soup and check out soup deposits
            if(seekLoc == null || rcLoc.distanceSquaredTo(thisSoupLoc) < soupDist) {   // If this soup is closer than closest soup
                seekLoc = thisSoupLoc;                                             // This soup is closest soup
                soupDist = rcLoc.distanceSquaredTo(seekLoc);                // Save distance to closest soup for multiple uses
            }

            cumulativeSoup += rc.senseSoup(thisSoupLoc);   // Check if a refinery is needed
            if(thisSoupLoc.distanceSquaredTo(hqLoc) > 4 && teamSoup >= 200) {
                centerX += thisSoupLoc.x;
                centerY += thisSoupLoc.y;
            }
        }

// Update soup node
        if(step < 10) soupArray[rcLoc.x / 12][rcLoc.y / 12] = cumulativeSoup;
        else if(cumulativeSoup > 0) {
            int nodeX = (rcLoc.x % 12) / 2;  // If in general middle of node
            int nodeY = (rcLoc.y % 12) / 2;
            if(nodeX == 1 && nodeY == 1) {
                int indexX = rcLoc.x / 12;
                int indexY = rcLoc.y / 12;
                if(soupArray[indexX][indexY] < cumulativeSoup) {
                    soupArray[indexX][indexY] = cumulativeSoup;  // Set node to soup amount
                    lowPriorityBlockchain(indexX, indexY, cumulativeSoup, 8080, 8080);    // Tell everyone about the soup node!
                }
            }
        }

        if(soupLocs.length > 0) {
            centerX /= soupLocs.length;
            centerY /= soupLocs.length;
        }

// Sense robots
        boolean refineryWasNull = (refineryLoc == null);
        boolean refineryNearby = false;
        for(RobotInfo nearbyRC : rc.senseNearbyRobots()) {
            if(nearbyRC.getTeam() == rcTeam) {
                if(nearbyRC.getType() == RobotType.REFINERY) {  // If find friendly refinery, save locally
                    refineryNearby = true;
                    refineryLoc = nearbyRC.getLocation();
                }
            } else if(nearbyRC.getType() == RobotType.DELIVERY_DRONE) { // Enemy drone nearby! Run to HQ!
                rcTask = 0;
                rcState = 2;
                seekLoc = hqLoc;
            }
        }
        
// Blockchain listening
        int[] listen = getBlockchain();
        if(listen != null) {
            MapLocation newLocation = new MapLocation(listen[0], listen[1]);
            switch(listen[4]) {
                case 127:   // Refinery
                    if(refineryLoc == null || refineryWasNull) {
                        refineryLoc = newLocation;
                        rcTask = 1; // Now build design school
                    }
                    if(cumulativeSoup == 0 || seekLoc == null || soupDepositLoc == null) {
                        //soupDepositLoc = newLocation;
                        seekLoc = newLocation;
                        refineryLoc = newLocation;
                    }

                    break;
                case 8080:  // Soup Node
                    soupArray[listen[0]][listen[1]] = listen[2];
                    
                    MapLocation newSoup = new MapLocation(listen[0] * 12 + 6, listen[1] * 12 + 6);
                    if(seekLoc == null) seekLoc = newSoup;
                    else if(rcLoc.distanceSquaredTo(seekLoc) > rcLoc.distanceSquaredTo(newSoup)) seekLoc = newSoup;


                    break;
                case 69:  // HQ Location was passed to created robot
                    if(listen[3] == 777 && rcTask == 1) rcTask = 0;  // Design School was created
                    System.out.println(rcTask + "---" + listen[3]);
                    break;
                default:
                    break;
            }
        }

// Conditions to build refinery
        if(cumulativeSoup >= 400 && !refineryNearby && centerX != 0 && centerY != 0 && rcLoc.distanceSquaredTo(hqLoc) > 35 && (refineryLoc == null || rcLoc.distanceSquaredTo(refineryLoc) > 35)) rcTask = 2;
        else if(rcTask == 2) rcTask = 0;

        if(seekLoc != null && hqLoc.distanceSquaredTo(seekLoc) > 25) soupDepositLoc = seekLoc;  // Set last found soup to last found soup


// Ready for action
        if(rc.isReady()) {  // Cooldown < 1
            int soupAmount = rc.getSoupCarrying();
            if(seekLoc != null && rcLoc.distanceSquaredTo(seekLoc) < rcRange && rc.senseSoup(seekLoc) < 1 && rcTask == 0) seekLoc = null;    // The soup is gone

            if(refineryLoc == null && teamSoup >= 150) {    // Haven't built refinery and can
                
            // Go far from HQ and build refinery
                if(!rc.canSenseLocation(hqLoc)) {
                    pathfind(hqLoc);
                    return;
                }

                Direction centerToHQ = mapCenter.directionTo(hqLoc);
                Direction hqToCenter = centerToHQ.opposite();
                Direction[] checkDirs = new Direction[]{centerToHQ, centerToHQ.rotateLeft(), centerToHQ.rotateRight(), hqToCenter.rotateRight().rotateRight(), hqToCenter.rotateLeft().rotateLeft(), hqToCenter.rotateRight(), hqToCenter.rotateLeft(), hqToCenter};
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
                if(optimalLoc != null) {
                    if(rcLoc.distanceSquaredTo(optimalLoc) < 3 && tryBuild(RobotType.REFINERY, rcLoc.directionTo(optimalLoc))) {
                        rcTask = 1;
                        refineryLoc = seekLoc;
                        lowPriorityBlockchain(refineryLoc.x, refineryLoc.y, 666, 13, 127);
                    }
                    else pathfind(optimalLoc.add(hqLoc.directionTo(optimalLoc)));
                }
            }
            
            Direction centerToHQ = mapCenter.directionTo(hqLoc);
            Direction hqToCenter = centerToHQ.opposite();
            Direction[] checkDirs;
            MapLocation optimalLoc = null;
            switch(rcTask) {
                case 0:

    // Searching for refinery
                    if(rcState == 2) {
                        MapLocation closestRefinery = refineryLoc;
                        if(closestRefinery == null) {
                            if(rc.getRoundNum() < 120 || teamSoup < 200) closestRefinery = hqLoc;
                            else if(teamSoup >= 200 && rcLoc.distanceSquaredTo(seekLoc) < 3 && tryBuildLoose(RobotType.REFINERY, hqLoc.directionTo(rcLoc))) {  // Try build refinery
                                rcTask = 1;
                                refineryLoc = rcLoc;
                                closestRefinery = refineryLoc;
                                lowPriorityBlockchain(refineryLoc.x, refineryLoc.y, 666, 13, 127);
                            }
                        }

                        if(closestRefinery != null) {
                            if(rcLoc.distanceSquaredTo(closestRefinery) < 3) {                      // HQ is neighbor?
                                RobotInfo closestR = rc.senseRobotAtLocation(closestRefinery);
                                if(closestR != null && rc.canDepositSoup(rcLoc.directionTo(closestRefinery))) rc.depositSoup(rcLoc.directionTo(closestRefinery), soupAmount);     // Deposit soup
                                else { //Go find new refinery!
                                    refineryLoc = null;
                                    rcState = 0;
                                    rcTask = 0;
                                }

                    // Find closest soup node
                                if(rc.getSoupCarrying() < 1) {
                                    rcState = 0;                // Go collect more soup!

                                    int indexX = rcLoc.x / 12;
                                    int indexY = rcLoc.y / 12;
                                    if(soupArray[indexX][indexY] > 0) {
                                        //soup

                                    }


                                    seekLoc = soupDepositLoc;   // Set soup to null unless an array of far away soup in 
                                    boundaryFollow = false;
                                }
                            }
                            else pathfind(closestRefinery);                      // HQ not neighbor? Keep searching for Refinery
                        }
                    }
    // Searching for soup
                    else if(rcState < 2) {

                        boolean wasMining = (rcState == 1) ? true : false;
                        rcState = 0;                                        // Searching for soup
                        for (Direction dir : Direction.allDirections()) {   // Try to mine soup everywhere
                            if(rc.canMineSoup(dir)) {                       // Mined soup? Stop searching
                                rc.mineSoup(dir);
                                rcState = 1;
                            }
                        }

                        soupAmount = rc.getSoupCarrying();

                        if(soupAmount == RobotType.MINER.soupLimit) {
                            seekLoc = hqLoc;
                            rcState = 2;   // Full? Go search for Refinery
                            if(boundaryFollow) {    // If had to bug in, reverse bug out
                                clockwise = !clockwise;
                                moveDir = moveDir.opposite();
                                consecutiveTurns =0;
                                startLoc = rcLoc;

                                dMin = Math.max(Math.abs(hqLoc.x - rcLoc.x), Math.abs(hqLoc.y - rcLoc.y));
                            }
                        }
                        else if(rcState == 0 && wasMining) seekLoc = null;   // If no more soup, but not full, restart search!

                        if(seekLoc != null) pathfind(seekLoc);
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

                            if(step % 20 == 0) {
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
                        seekLoc = null;
                    }

                    break;
// Build Design School
                case 1:

                    if(teamSoup < 150) break;
                    if(!rc.canSenseLocation(hqLoc)) {
                        pathfind(hqLoc);
                        return;
                    }
    
                    checkDirs = new Direction[]{centerToHQ, centerToHQ.rotateLeft(), centerToHQ.rotateRight(), hqToCenter.rotateRight().rotateRight(), hqToCenter.rotateLeft().rotateLeft(), hqToCenter.rotateRight(), hqToCenter.rotateLeft(), hqToCenter};
                    for(Direction checkDir : checkDirs) {
                        MapLocation checkLoc = hqLoc.add(checkDir).add(checkDir).add(checkDir);
                        if(checkDir.dx == 0 || checkDir.dy == 0) checkLoc = checkLoc.add(checkDir);
                        if(rcLoc.distanceSquaredTo(checkLoc) < 3) {
                            if(tryBuild(RobotType.DESIGN_SCHOOL, rcLoc.directionTo(checkLoc))) {
                                lowPriorityBlockchainHold = new int[]{hqLoc.x, hqLoc.y, 69, 777, 69, 2}; // Offset by 2 in case pollution bogs it down
                                rcTask = 5;
                            }
                        }
                        else if(rc.canSenseLocation(checkLoc) && !rc.isLocationOccupied(checkLoc)) optimalLoc = checkLoc;
                    }
                    if(optimalLoc != null) pathfind(optimalLoc);







                    break;
// Build Refinery
                case 2:
                    if(seekLoc == null) {
                        rcTask = 0;
                        break;
                    }

                    if(/*teamSoup >= 200 && */rcLoc.distanceSquaredTo(seekLoc) < 3 && tryBuildLoose(RobotType.REFINERY, rcLoc.directionTo(seekLoc))) {  // Try build refinery
                        rcTask = 0;
                        refineryLoc = seekLoc;
                        lowPriorityBlockchain(refineryLoc.x, refineryLoc.y, 666, 13, 127);
                    } else pathfind(seekLoc);
                    break;
// Build Drone Factory
                case 3:
                //if(teamSoup < 150) break;
                if(!rc.canSenseLocation(hqLoc)) {
                    pathfind(hqLoc);
                    return;
                }

                checkDirs = new Direction[]{centerToHQ, centerToHQ.rotateLeft(), centerToHQ.rotateRight(), hqToCenter.rotateRight().rotateRight(), hqToCenter.rotateLeft().rotateLeft(), hqToCenter.rotateRight(), hqToCenter.rotateLeft(), hqToCenter};
                for(Direction checkDir : checkDirs) {
                    MapLocation checkLoc = hqLoc.add(checkDir).add(checkDir).add(checkDir);
                    if(checkDir.dx == 0 || checkDir.dy == 0) checkLoc = checkLoc.add(checkDir);
                    if(rcLoc.distanceSquaredTo(checkLoc) < 3) {
                        if(tryBuild(RobotType.FULFILLMENT_CENTER, rcLoc.directionTo(checkLoc))) {
                            lowPriorityBlockchainHold = new int[]{hqLoc.x, hqLoc.y, 69, 888, 69, 2}; // Offset by 2 in case pollution bogs it down
                            rcTask = 4;
                        }
                    }
                    else if(rc.canSenseLocation(checkLoc) && !rc.isLocationOccupied(checkLoc)) optimalLoc = checkLoc;
                }
                if(optimalLoc != null) pathfind(optimalLoc);
                break;
// Build Net Gun
                case 4:
                    //if(teamSoup < 250) break;
                    // Go far from HQ and build refinery
    
                    if(!rc.canSenseLocation(hqLoc)) {
                        pathfind(hqLoc);
                        return;
                    }
    
                    checkDirs = new Direction[]{centerToHQ, centerToHQ.rotateLeft(), centerToHQ.rotateRight(), hqToCenter.rotateRight().rotateRight(), hqToCenter.rotateLeft().rotateLeft(), hqToCenter.rotateRight(), hqToCenter.rotateLeft(), hqToCenter};
                    for(Direction checkDir : checkDirs) {
                        MapLocation checkLoc = hqLoc.add(checkDir).add(checkDir).add(checkDir);
                        if(checkDir.dx == 0 || checkDir.dy == 0) checkLoc = checkLoc.add(checkDir);
                        if(rcLoc.distanceSquaredTo(checkLoc) < 3) {
                            if(tryBuild(RobotType.NET_GUN, rcLoc.directionTo(checkLoc))) {
                                //lowPriorityBlockchainHold = new int[]{hqLoc.x, hqLoc.y, 69, 777, 69, 2}; // Offset by 2 in case pollution bogs it down
                                rcTask = 3;
                            }
                        }
                        else if(rc.canSenseLocation(checkLoc) && !rc.isLocationOccupied(checkLoc)) optimalLoc = checkLoc;
                    }
                    if(optimalLoc != null) pathfind(optimalLoc);
                    break;
// Build Vaporator
                case 5:


                    //if(teamSoup < 500) break;
                    // Go far from HQ and build refinery

                    if(!rc.canSenseLocation(hqLoc) || rc.getRoundNum() <= 500) {
                        pathfind(hqLoc);
                        return;
                    }

                    checkDirs = new Direction[]{centerToHQ, centerToHQ.rotateLeft(), centerToHQ.rotateRight(), hqToCenter.rotateRight().rotateRight(), hqToCenter.rotateLeft().rotateLeft(), hqToCenter.rotateRight(), hqToCenter.rotateLeft(), hqToCenter};
                    for(Direction checkDir : checkDirs) {
                        MapLocation checkLoc = hqLoc.add(checkDir).add(checkDir).add(checkDir);
                        if(checkDir.dx == 0 || checkDir.dy == 0) checkLoc = checkLoc.add(checkDir);
                        if(rcLoc.distanceSquaredTo(checkLoc) < 3) {
                            if(tryBuild(RobotType.VAPORATOR, rcLoc.directionTo(checkLoc))) {
                                //lowPriorityBlockchainHold = new int[]{hqLoc.x, hqLoc.y, 69, 777, 69, 2}; // Offset by 2 in case pollution bogs it down
                                rcTask = 4;
                            }
                        }
                        else if(rc.canSenseLocation(checkLoc) && !rc.isLocationOccupied(checkLoc)) optimalLoc = checkLoc;
                    }
                    if(optimalLoc != null) pathfind(optimalLoc);

                    /*//if(teamSoup < 500) break;
                    // Build Vaporators everywhere high!
                    //if(!rc.canSenseLocation(hqLoc)) {
                    if(rcLoc.distanceSquaredTo(hqLoc) > 35) pathfind(hqLoc);
                        //return;
                    //}
                    //checkDirs = new Direction[]{centerToHQ, centerToHQ.rotateLeft(), centerToHQ.rotateRight(), hqToCenter.rotateRight().rotateRight(), hqToCenter.rotateLeft().rotateLeft(), hqToCenter.rotateRight(), hqToCenter.rotateLeft(), hqToCenter};
                    for(Direction checkDir : Direction.allDirections()) {
                        MapLocation checkLoc = rcLoc.add(checkDir);
                        if(rc.canSenseLocation(checkLoc) && rc.senseElevation(checkLoc) > 9) tryBuild( (rc.getRoundNum() % 6 == 0) ? RobotType.NET_GUN : RobotType.VAPORATOR, checkDir);
                    }
                    if(rc.getRoundNum() <= 500) pathfind(hqLoc);
                    else if(rcLoc.distanceSquaredTo(hqLoc) < 9) tryMoveLoose(hqLoc.directionTo(rcLoc));
                    else if(!tryMoveLoose(clockwise ? rcLoc.directionTo(hqLoc).rotateRight().rotateRight() : rcLoc.directionTo(hqLoc).rotateLeft().rotateLeft())) clockwise = !clockwise;
                    //else if(rcLoc.distanceSquaredTo(hqLoc) > 35) tryMoveLoose(rcLoc.directionTo(hqLoc));
                    //pathfind(hqLoc.add(hqLoc.directionTo(rcLoc).rotateLeft().rotateLeft()).add(hqLoc.directionTo(rcLoc).rotateLeft()));
                    //tryMoveLoose(rcLoc.directionTo(hqLoc).rotateRight().rotateRight());
                    //if(optimalLoc != null) pathfind(optimalLoc);*/
                    break;
                default:
                    break;
            }
        }

        if(seekLoc != null) {
            if(rcTask == 0) {
                if(rcState == 0) rc.setIndicatorDot(seekLoc, 255, 255, 255);
                else if(rcState == 1) rc.setIndicatorDot(seekLoc, 255, 200, 200);
                else if(rcState == 2) rc.setIndicatorDot(seekLoc, 255, 100, 100);
            }
            else if(rcTask == 1) rc.setIndicatorDot(seekLoc, 200, 200, 255);
            else if(rcTask == 2) rc.setIndicatorDot(seekLoc, 100, 100, 255);
            else if(rcTask == 3) rc.setIndicatorDot(seekLoc, 0, 0, 255);
            else if(rcTask == 4) rc.setIndicatorDot(seekLoc, 0, 0, 200);

        }
    }



    /**
     *  Caste ray from rc to end,
     *  if hit barrier, minimal A* the way out,
     *  if stuck in concave shape, bug it out
     */
    static void pathfind(MapLocation endLoc) throws GameActionException {
        if(!rc.isReady()) return;
        int rcRange = rc.getCurrentSensorRadiusSquared();

        int startEndX = endLoc.x - rcLoc.x;                 // Vector from start to end
        int startEndY = endLoc.y - rcLoc.y;

        int checkX = 0; // Vector from start to checking tile
        int checkY = 0;
        int checkingIndex; // Index of checking tile in localMap

        Direction optimalDir = Direction.CENTER;
        int optimalCost = (int)Math.max(Math.abs(startEndX), Math.abs(startEndY));  // Min amount of moves needed to get from start to end

        if(boundaryFollow) {    // Follow boundary out of concave shape
            
            moveDir = clockwise ? moveDir.rotateRight() : moveDir.rotateLeft();

            MapLocation checkLoc = rcLoc.add(moveDir);
            int i = 0;
            while(i < 8) {
                if(!rc.canSenseLocation(checkLoc) || (Math.abs(rc.senseElevation(checkLoc) - rcElev) > 3 || rc.senseFlooding(checkLoc) || rc.isLocationOccupied(checkLoc))) { // Hit something
                    moveDir = clockwise ? moveDir.rotateLeft() : moveDir.rotateRight();
                    checkLoc = rcLoc.add(moveDir);
                } else break;
                i++;
            }

            if(i < 8 && rc.canMove(moveDir)) rc.move(moveDir);
            if(moveDir.dx != 0 && moveDir.dy != 0) {
                if(i == 0) consecutiveTurns++;
                else consecutiveTurns = 0;

                if(consecutiveTurns < 4) moveDir = clockwise ? moveDir.rotateRight() : moveDir.rotateLeft();
                else {
                    moveDir = clockwise ? moveDir.opposite().rotateLeft() : moveDir.opposite().rotateRight();
                    consecutiveTurns = 1;
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
                checkX += d.dx;
                checkY += d.dy;
                MapLocation checkLoc = rcLoc.translate(checkX, checkY);

                if(rcLoc.x + checkX == endLoc.x && rcLoc.y + checkY == endLoc.y) {  // Ray hit endLoc
                    optimalDir = rcLoc.directionTo(checkLoc);    // Simply point in direction of end, no cost calculation needed
                    break;
                }

                checkingIndex = 54 + 11 * checkX - checkY;     // Convert tile coordinates relative to rcLoc to checkingIndex of sensor
                if(checkingIndex > 92) {
                    if(checkingIndex < 103) checkingIndex--;
                    else checkingIndex -= 4;
                } else if(checkingIndex < 16) {
                    if(checkingIndex > 6) checkingIndex++;
                    else checkingIndex += 4; 
                }

                if(rc.canSenseLocation(checkLoc) ? (Math.abs(rc.senseElevation(checkLoc) - rcElev) > 3 || rc.senseFlooding(checkLoc) || rc.isLocationOccupied(checkLoc)) : false) {  // Hit something

                    //dCost = Math.max(Math.abs(checkX - d.dx), Math.abs(checkY - d.dy)) + Math.max(Math.abs(startEndX - (checkX - d.dx)), Math.abs(startEndY - (checkY  - d.dy))); // Get optimal cost of tile before collision
                    if(checkX - d.dx + checkY - d.dy != 0) optimalCost = Math.max(Math.abs(checkX - d.dx), Math.abs(checkY - d.dy)) + Math.max(Math.abs(startEndX - (checkX - d.dx)), Math.abs(startEndY - (checkY  - d.dy))); // Get optimal cost of tile before collision
                    startEndX = checkX; // Vector from start to collided tile
                    startEndY = checkY;

                    int startEndXRight = checkX;
                    int startEndYRight = checkY;

                    int leftDone = 0;   // Finish search if ray castes to end of sensor range
                    int rightDone = 0;
    
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

                            checkX = 0; // Vector used to change update
                            checkY = 0;

                            while(checkX * checkX + checkY * checkY < rcRange) {    // Check tiles in a ray until a barrier is hit, end is hit, or ray escapes range
                                d = rcLoc.directionTo(new MapLocation(rcLoc.x + startEndX, rcLoc.y + startEndY));
                                checkX += d.dx;
                                checkY += d.dy;
                                checkLoc = rcLoc.translate(checkX, checkY);

                                checkingIndex = 54 + 11 * checkX - checkY; // Convert tile coordinates relative to start to checkingIndex
                                if(checkingIndex > 92) {
                                    if(checkingIndex < 103) checkingIndex--;
                                    else checkingIndex -= 4;
                                } else if(checkingIndex < 16) {
                                    if(checkingIndex > 6) checkingIndex++;
                                    else checkingIndex += 4;
                                }

                                if(rc.canSenseLocation(checkLoc) ? (Math.abs(rc.senseElevation(checkLoc) - rcElev) > 3 || rc.senseFlooding(checkLoc) || rc.isLocationOccupied(checkLoc)) : false) {  // Hit something
                                    if(checkX - d.dx + checkY - d.dy != 0) {
                                        int checkCost = Math.max(Math.abs(checkX - d.dx), Math.abs(checkY - d.dy)) + Math.max(Math.abs(endLoc.x - rcLoc.x - (checkX - d.dx)), Math.abs(endLoc.y - rcLoc.y - (checkY - d.dy)));
                                        if(checkCost < optimalCost) {
                                            optimalCost = checkCost;
                                            optimalDir = rcLoc.directionTo(new MapLocation(checkX - d.dx + rcLoc.x, checkY - d.dy + rcLoc.y));
                                        }
                                    }

                                    break;
                                }

                                if(checkX == startEndX && checkY == startEndY) {    // Found offset end
                                    int checkCost = Math.max(Math.abs(checkX), Math.abs(checkY)) + Math.max(Math.abs(endLoc.x - rcLoc.x - checkX), Math.abs(endLoc.y - rcLoc.y - checkY));
                                    if(checkCost <= optimalCost) {
                                        optimalCost = checkCost;
                                        optimalDir = rcLoc.directionTo(new MapLocation(checkX + rcLoc.x, checkY + rcLoc.y));
                                    }
                                    leftDone = 10;
                                    break;
                                }
                            }
                            leftDone++;
                        }
                    
                        if(rightDone < 6) { 
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
                                d = rcLoc.directionTo(new MapLocation(rcLoc.x + startEndXRight, rcLoc.y + startEndYRight));
                                checkX += d.dx;
                                checkY += d.dy;
                                checkLoc = rcLoc.translate(checkX, checkY);

                                checkingIndex = 54 + 11 * checkX - checkY; // Convert tile coordinates relative to start to checkingIndex
                                if(checkingIndex > 92) {
                                    if(checkingIndex < 103) checkingIndex--;
                                    else checkingIndex -= 4;
                                } else if(checkingIndex < 16) {
                                    if(checkingIndex > 6) checkingIndex++;
                                    else checkingIndex += 4;
                                }

                                if(rc.canSenseLocation(checkLoc) ? (Math.abs(rc.senseElevation(checkLoc) - rcElev) > 3 || rc.senseFlooding(checkLoc) || rc.isLocationOccupied(checkLoc)) : false) {  // Hit something
                                    if(checkX - d.dx + checkY - d.dy != 0) {
                                        int checkCost = Math.max(Math.abs(checkX - d.dx), Math.abs(checkY - d.dy)) + Math.max(Math.abs(endLoc.x - rcLoc.x - (checkX - d.dx)), Math.abs(endLoc.y - rcLoc.y - (checkY - d.dy)));
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

        // Sense HQ

        if(hqLoc == null) { // Find and save hq location if can
            if(rc.sensePollution(rcLoc) == 0) {
                RobotInfo[] nearbyRobots = rc.senseNearbyRobots(rc.getCurrentSensorRadiusSquared(), rcTeam);
                for (int i = 0; i < nearbyRobots.length; i++) {         // HQ location must be a neighbor
                    if (nearbyRobots[i].getType() == RobotType.HQ) {
                        hqLoc = nearbyRobots[i].getLocation();
                        break;
                    }
                }
            } else {    // See if hqLoc was sent in blockchain
                int[] message = getBlockchain();
                if(message != null && message[2] == 69)  hqLoc = new MapLocation(message[0], message[1]);
            }
        }
        if(hqLoc == null) {
            System.out.println("Moma bear hain't found Papa bear");
            return;   // Don't do anything without hqLoc
        }

        if(rc.getTeamSoup() >= 150 && minersCreated < 8 && tryBuildLoose(RobotType.LANDSCAPER, rcLoc.directionTo(hqLoc))) {
            lowPriorityBlockchainHold = new int[]{hqLoc.x, hqLoc.y, 69, 42, 69, 2};
            minersCreated++;
        }
    }

    /**
     * Defensive strategy, turtle HQ
     * 
     * 
     * 
     */
    static void runLandscaper() throws GameActionException {

// Sense HQ
        if(hqLoc == null) { // Find and save hq location if can
            if(rc.sensePollution(rcLoc) == 0) {
                RobotInfo[] nearbyRobots = rc.senseNearbyRobots(rc.getCurrentSensorRadiusSquared(), rcTeam);
                for (int i = 0; i < nearbyRobots.length; i++) {         // HQ location must be a neighbor
                    if (nearbyRobots[i].getType() == RobotType.HQ) {
                        hqLoc = nearbyRobots[i].getLocation();
                        break;
                    }
                }
            } else {    // See if hqLoc was sent in blockchain
                int[] message = getBlockchain();
                if(message != null && message[2] == 69)  hqLoc = new MapLocation(message[0], message[1]);
            }
        }
        if(hqLoc == null || !rc.isReady()) return;   // Don't do anything without hqLoc or if not cooled down


        
// Sense nearby robots, bury enemy buildings, count friendly landscapers
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(rc.getCurrentSensorRadiusSquared());
        MapLocation nrcLoc;
        //int hqGuards = 0;
        for(RobotInfo nrc : nearbyRobots) {
            RobotType nrcType = nrc.getType();

            if(nrc.getTeam() != rc.getTeam()) {
                if(nrcType == RobotType.DESIGN_SCHOOL ||  nrcType == RobotType.FULFILLMENT_CENTER || nrcType == RobotType.NET_GUN || nrcType == RobotType.REFINERY || nrcType == RobotType.VAPORATOR) {
                    nrcLoc = nrc.getLocation();
                    if(rcLoc.distanceSquaredTo(nrcLoc) < 3) {   // Bury enemy buildings
                        Direction rcToEnemy = rcLoc.directionTo(nrcLoc);
                        if(rc.canDepositDirt(rcToEnemy)) rc.depositDirt(rcToEnemy);
                    } else pathfind(nrcLoc);
                }
            } //else if(nrcType == RobotType.LANDSCAPER && nrc.getLocation().distanceSquaredTo(hqLoc) < 3) hqGuards++;    // 2 HQ Guards needed at start
        }

        //if(hqGuards < 2 && rc.canSenseLocation(hqLoc.add(rcLoc.directionTo(hqLoc)))) rcState = 0;
        //else if(hqGuards > 1 && rcState == 0) rcState = 1;

        if(rc.getRoundNum() > 500) {

    // Dig dirt from HQ pits
            Direction hqDirRC = hqLoc.directionTo(rcLoc);
            if(rc.getDirtCarrying() == 0) {
                if(rcLoc.distanceSquaredTo(hqLoc) < 3) {
                    if(hqDirRC.dx != 0 && hqDirRC.dy != 0) {
                        if(rc.canDigDirt(hqDirRC.rotateLeft().rotateLeft())) rc.digDirt(hqDirRC.rotateLeft().rotateLeft());
                        else if(rc.canDigDirt(hqDirRC.rotateLeft().rotateLeft())) rc.digDirt(hqDirRC.rotateLeft().rotateLeft());
                    } else {
                        if(rc.canDigDirt(hqDirRC)) rc.digDirt(hqDirRC);
                        else if(rc.canDigDirt(hqDirRC)) rc.digDirt(hqDirRC);
                    }
                    if(!rc.isReady()) return;
                } else if(rcLoc.distanceSquaredTo(hqLoc) == 5) {
                    if(rc.canDigDirt(Direction.NORTH) && hqLoc.distanceSquaredTo(rcLoc.add(Direction.NORTH)) == 4) rc.digDirt(Direction.NORTH);
                    else if(rc.canDigDirt(Direction.EAST) && hqLoc.distanceSquaredTo(rcLoc.add(Direction.EAST)) == 4) rc.digDirt(Direction.EAST);
                    else if(rc.canDigDirt(Direction.SOUTH) && hqLoc.distanceSquaredTo(rcLoc.add(Direction.SOUTH)) == 4) rc.digDirt(Direction.SOUTH);
                    else if(rc.canDigDirt(Direction.WEST) && hqLoc.distanceSquaredTo(rcLoc.add(Direction.WEST)) == 4) rc.digDirt(Direction.WEST);
                } else if(rcLoc.distanceSquaredTo(hqLoc) == 8) {
                    if(rc.canDigDirt(hqDirRC.rotateLeft())) rc.digDirt(hqDirRC.rotateLeft());
                } else if(rcLoc.distanceSquaredTo(hqLoc) == 9 || rcLoc.distanceSquaredTo(hqLoc) == 10) {
                    if(rc.canDigDirt(hqDirRC.opposite())) rc.digDirt(hqDirRC.opposite());
                } else if(rcLoc.distanceSquaredTo(hqLoc) == 13) {
                    if(rc.canDigDirt(hqDirRC.rotateLeft().rotateLeft()) && hqLoc.distanceSquaredTo(rcLoc.add(hqDirRC.rotateLeft().rotateLeft())) == 13) rc.digDirt(hqDirRC.rotateLeft().rotateLeft());
                }
            }

    // Dig way to battle station
            if(rcLoc.distanceSquaredTo(hqLoc) > 2) {
                pathfind(hqLoc);
                Direction rcDirHQ = hqDirRC.opposite();
                Direction[] checkDirs = new Direction[]{rcDirHQ, rcDirHQ.rotateLeft(), rcDirHQ.rotateRight()};

                for(Direction checkDir : checkDirs) {
                    if(!rc.isReady()) return;
                    if(hqLoc.distanceSquaredTo(rcLoc.add(checkDir)) == 4 || rc.isLocationOccupied(rcLoc.add(checkDir))) continue;

                    int elevDelta = rc.senseElevation(rcLoc.add(checkDir)) - rc.senseElevation(rcLoc);
                    
                    if(elevDelta > 3) {    // Dig there
                        if(rc.canDigDirt(checkDir)) rc.digDirt(checkDir);
                        else if(rc.canDepositDirt(Direction.CENTER)) rc.depositDirt(Direction.CENTER);
                    } else if(elevDelta < -3) { // Dig here
                        if(rc.canDepositDirt(checkDir)) rc.depositDirt(checkDir);
                        else if(rc.canDigDirt(Direction.CENTER)) rc.digDirt(Direction.CENTER);
                    } else if(rc.canMove(checkDir) && rc.canSenseLocation(rcLoc.add(checkDir)) && !rc.senseFlooding(rcLoc.add(checkDir))) rc.move(checkDir);
                }
                if(rc.canDepositDirt(Direction.CENTER)) rc.depositDirt(Direction.CENTER);

    // Fortify HQ
            } else {
                if(hqDirRC != hqLoc.directionTo(mapCenter)) {    // Prioritize front of hq
                    int curDistCenter = rcLoc.distanceSquaredTo(mapCenter);
                    for(Direction checkDir : Direction.allDirections()) {
                        MapLocation checkLoc = rcLoc.add(checkDir);
                        if(checkLoc.distanceSquaredTo(mapCenter) < curDistCenter && checkLoc.distanceSquaredTo(hqLoc) < 3 && rc.canMove(checkDir) && rc.canSenseLocation(checkLoc) && !rc.senseFlooding(checkLoc)) rc.move(checkDir);
                    }
                }

                if(rc.getDirtCarrying() > 0) {  // If have dirt, find optimal location to place dirt
                    Direction optimalDir = Direction.CENTER;    // Dump on lowest elevation
                    int lowestElev = Integer.MAX_VALUE;
                    for(Direction checkDir : Direction.allDirections()) {
                        MapLocation checkLoc = rcLoc.add(checkDir);
                        if(!rc.canSenseLocation(checkLoc)) continue;
                        int checkElev = rc.senseElevation(checkLoc);
                        if(checkElev < lowestElev && rc.canDepositDirt(checkDir) && !checkLoc.equals(hqLoc) && ((checkLoc.distanceSquaredTo(hqLoc) != 4 && rc.getRoundNum() < 100) || (checkLoc.distanceSquaredTo(hqLoc) < 3 && rc.getRoundNum() >= 100))) {
                            lowestElev = checkElev;
                            optimalDir = checkDir;
                        }
                    }
                    if(rc.getRoundNum() < 600 && lowestElev > 3) optimalDir = Direction.CENTER;
                    if(lowestElev < rcElev - 3 && rc.canDepositDirt(optimalDir)) rc.depositDirt(optimalDir);
                    else if(rc.canDepositDirt(Direction.CENTER)) rc.depositDirt(Direction.CENTER);
                }
            }
// Terraform around HQ CASE: Low ground
        } else {

            // Stay out of pits!
            MapLocation nearestPit = hqLoc.add(Direction.NORTH).add(Direction.NORTH);
            MapLocation[] checkLocs = new MapLocation[]{
                new MapLocation(hqLoc.x, hqLoc.y - 2),
                new MapLocation(hqLoc.x + 2, hqLoc.y),
                new MapLocation(hqLoc.x - 2, hqLoc.y),
                new MapLocation(hqLoc.x + 3, hqLoc.y + 2),
                new MapLocation(hqLoc.x - 3, hqLoc.y - 2),
                new MapLocation(hqLoc.x + 2, hqLoc.y - 3),
                new MapLocation(hqLoc.x - 2, hqLoc.y + 3)
            };

            for(MapLocation checkLoc : checkLocs) 
                if(rcLoc.distanceSquaredTo(checkLoc) < rcLoc.distanceSquaredTo(nearestPit)) nearestPit = checkLoc;
            
            if(rcLoc.equals(nearestPit)) tryMoveLoose(hqLoc.directionTo(rcLoc));
            if(!rc.isReady()) return;

            if(rc.getDirtCarrying() == 0) rcState = 1;
            if(rcState == 1) {
                
                Direction rcToPit = rcLoc.directionTo(nearestPit);
                if(nearestPit != null && rcLoc.distanceSquaredTo(nearestPit) > 2) pathfind(nearestPit);
                rc.setIndicatorDot(nearestPit, 0, 255, 255);
                
                if(rcLoc.distanceSquaredTo(nearestPit) < 3 && rc.canDigDirt(rcToPit)) rc.digDirt(rcToPit);
                if(rc.getDirtCarrying() == 25) rcState = 2;

            } else if(rcState == 2) {
                int lowestElev = Integer.MAX_VALUE;
                Direction lowestDir = Direction.CENTER;

                for(Direction checkDir : Direction.allDirections()) {
                    MapLocation checkLoc = rcLoc.add(checkDir);
                    if(hqLoc.distanceSquaredTo(checkLoc) < 5 || (hqLoc.distanceSquaredTo(checkLoc) == 13 && hqLoc.distanceSquaredTo(checkLoc.add(hqLoc.directionTo(checkLoc).rotateLeft())) == 18) || !rc.canSenseLocation(checkLoc) || (rc.isLocationOccupied(checkLoc) && !checkLoc.equals(rcLoc))) continue;
                    int checkElev = rc.senseElevation(checkLoc);
                    if(lowestElev > checkElev && checkElev >= -100) {
                        lowestElev = checkElev;
                        lowestDir = checkDir;
                    }
                }
                if((lowestElev < (rc.getRoundNum() / 50 + 2) && hqLoc.distanceSquaredTo(rcLoc.add(lowestDir)) < 42) || lowestElev < (rc.getRoundNum() / 50 - 1)) rc.depositDirt(lowestDir);
                else if(!tryMoveLoose(clockwise ? rcLoc.directionTo(hqLoc).rotateLeft().rotateLeft() : rcLoc.directionTo(hqLoc).rotateRight().rotateRight())) {
                    clockwise = !clockwise;
                    tryMoveLoose(clockwise ? rcLoc.directionTo(hqLoc).rotateLeft().rotateLeft() : rcLoc.directionTo(hqLoc).rotateRight().rotateRight());
                }


                if(rc.getDirtCarrying() == 0 || rcLoc.distanceSquaredTo(hqLoc) > 35) rcState = 1;
            }




            // TRY 2
            /*int highestElev = Integer.MIN_VALUE, lowestElev = Integer.MAX_VALUE;
            Direction highestDir = null, lowestDir = null;
            for(Direction checkDir : Direction.allDirections()) {
                MapLocation checkLoc = rcLoc.add(checkDir);
                if(hqLoc.distanceSquaredTo(checkLoc) < 5 || !rc.canSenseLocation(checkLoc) || (rc.isLocationOccupied(checkLoc) && !checkLoc.equals(rcLoc))) continue;
                int checkElev = rc.senseElevation(checkLoc);
                if(highestElev < checkElev && checkElev <= 100) {
                    highestElev = checkElev;
                    highestDir = checkDir;
                } else if(lowestElev > checkElev && checkElev >= -100) {
                    lowestElev = checkElev;
                    lowestDir = checkDir;
                }
            }
            System.out.println("H: " + highestElev + ", L: " + lowestElev);
            if(highestElev - lowestElev > 3) {
                if(rc.getDirtCarrying() == 0 && highestDir != null) rc.digDirt(highestDir);
                else if(lowestDir != null) rc.depositDirt(lowestDir);
            } else {
                //pathfind(mapCenter);
                //Direction tryDir = rcLoc.directionTo(hqLoc).rotateLeft().rotateLeft();
                tryMoveLoose(rcLoc.directionTo(hqLoc).rotateLeft().rotateLeft());
                //if(rc.canMove(tryDir)) rc.move(tryDir);
                //else if(rc.canMove(tryDir.opposite())) rc.move(tryDir.opposite());
            }*/







            
            // TRY 1
            /*if(rcLoc.distanceSquaredTo(hqLoc) < 25) pathfind(mapCenter);
            
                //pathfind(mapCenter);
                //return;
            //}
// Dig dirt
            //if(rc.getDirtCarrying() == 0) {
                    for(Direction checkDir : Direction.allDirections()) 
                        if(rc.canSenseLocation(rcLoc.add(checkDir)) && rc.senseElevation(rcLoc.add(checkDir)) > (rc.getRoundNum() / 100 + 5) + 2 && rc.canDigDirt(checkDir)) rc.digDirt(checkDir);
                    for(Direction checkDir : Direction.allDirections()) 
                        if(!((rcLoc.x + hqLoc.x + checkDir.dx) % 2 == 0 ^ (rcLoc.y + hqLoc.y + checkDir.dy) % 2 == 0) && rc.canDigDirt(checkDir)) rc.digDirt(checkDir);
                    
// Dump dirt            
            //} else {//if(rcState == 1) {
            if(rc.isReady()) {
                Direction[] checkDirs = Direction.allDirections();
                for(int i=8; i >=0; i--) {
                    MapLocation checkLoc = rcLoc.add(checkDirs[i]);
                    int checkElev = -99;
                    if(rc.canSenseLocation(checkLoc)) checkElev = rc.senseElevation(checkLoc);
                    if(((rcLoc.x + hqLoc.x + checkDirs[i].dx) % 2 == 1 ^ (rcLoc.y + hqLoc.y + checkDirs[i].dy) % 2 == 1) && checkElev > -99 && checkElev < (rc.getRoundNum() / 100 + 5) && rc.canDepositDirt(checkDirs[i])) rc.depositDirt(checkDirs[i]);
                }
                //rcState = 2;
            }
            if(rc.isReady()) {
                //if(rc.canMove(rcLoc.directionTo(mapCenter))) rc.move(rcLoc.directionTo(mapCenter));
                Direction diagDir = hqLoc.directionTo(rcLoc);
                if((diagDir.dx == 0 || diagDir.dy == 0)) {
                    if(bool) diagDir = diagDir.rotateLeft();
                    else diagDir = diagDir.rotateRight();
                    bool = !bool;
                } 
                if(!((rcLoc.x + hqLoc.x) % 2 == 0 ^ (rcLoc.y + hqLoc.y) % 2 == 0)) diagDir = diagDir.rotateLeft();
                if(rc.canMove(diagDir)) rc.move(diagDir);
                if(bool) diagDir = diagDir.rotateLeft().rotateLeft();
                else diagDir = diagDir.rotateRight().rotateRight();
                if(rc.canMove(diagDir)) rc.move(diagDir);
                diagDir = diagDir.opposite();
                if(rc.canMove(diagDir)) rc.move(diagDir);
                //if(bool) diagDir = diagDir.rotateRight().rotateRight();
                //else diagDir = diagDir.rotateLeft().rotateLeft();
                //if(rc.canMove(diagDir)) rc.move(diagDir);
                //rcState = 1;
            }*/
        }
        
    }

    

    static void runFulfillmentCenter() throws GameActionException {

        // Sense HQ
        if(hqLoc == null) { // Find and save hq location if can
            if(rc.sensePollution(rcLoc) == 0) {
                RobotInfo[] nearbyRobots = rc.senseNearbyRobots(rc.getCurrentSensorRadiusSquared(), rcTeam);
                for (int i = 0; i < nearbyRobots.length; i++) {         // HQ location must be a neighbor
                    if (nearbyRobots[i].getType() == RobotType.HQ) {
                        hqLoc = nearbyRobots[i].getLocation();
                        break;
                    }
                }
            } else {    // See if hqLoc was sent in blockchain
                int[] message = getBlockchain();
                if(message != null && message[2] == 69)  hqLoc = new MapLocation(message[0], message[1]);
            }
        }
        if(hqLoc == null) {
            System.out.println("Uncle bear hain't found Papa bear");
            return;   // Don't do anything without hqLoc
        }

        if(rc.getTeamSoup() >= 150 && tryBuildLoose(RobotType.DELIVERY_DRONE, rcLoc.directionTo(hqLoc))) {
            lowPriorityBlockchainHold = new int[]{hqLoc.x, hqLoc.y, 69, 43, 69, 3};
            minersCreated++;
        }
    }


    static void runDeliveryDrone() throws GameActionException {
    	if(!rc.isCurrentlyHoldingUnit()) {
    		// Sense HQ
            if(hqLoc == null) { // Find and save hq location if can
                if(rc.sensePollution(rcLoc) == 0) {
                    RobotInfo[] nearbyRobots = rc.senseNearbyRobots(rc.getCurrentSensorRadiusSquared(), rcTeam);
                    for (int i = 0; i < nearbyRobots.length; i++) {         // HQ location must be a neighbor
                        if (nearbyRobots[i].getType() == RobotType.HQ) {
                            hqLoc = nearbyRobots[i].getLocation();
                            break;
                        }
                    }
                } else {    // See if hqLoc was sent in blockchain
                    int[] message = getBlockchain();
                    if(message != null && message[2] == 69)  hqLoc = new MapLocation(message[0], message[1]);
                }
            }
            Direction direction = randomDirection();
            MapLocation cowLocation = findClosestCowOrEnemy();
            if(cowLocation == null) {
            	if(hqLoc == null) {
            		RobotInfo[] nearbyRobots = rc.senseNearbyRobots(rc.getCurrentSensorRadiusSquared(), rcTeam);
                	if(nearbyRobots.length > 0) {
                		if(!tryMove(rc.getLocation().directionTo(nearbyRobots[nearbyRobots.length-1].getLocation()))) {
                			tryMove(randomDirection());
                		}
                	}
                } else {
                	direction = rc.getLocation().directionTo(hqLoc);
                	//TODO: make drones circle the hq when there is no enemies or cows around
                	if(rc.getLocation().distanceSquaredTo(hqLoc)<=18) {
                		rotateAroundHQ();
                	}
                	
                }
            } else {
            	direction = rc.getLocation().directionTo(cowLocation);
            }
            RobotInfo [] ri = rc.senseNearbyRobots(rc.getCurrentSensorRadiusSquared(), Team.NEUTRAL);
            for (int i = 0; i < ri.length; i++) {
            	if(ri[i].getType() == RobotType.COW) {
            		if(rc.canPickUpUnit(ri[i].ID)) {
            			rc.pickUpUnit(ri[i].ID);
            			return;
            		}
            	}
            }
            if(!tryMove(direction)) {
            	tryMove(randomDirection());
            }
    	} else {
    		Direction [] directions = {
    		        Direction.NORTH,
    		        Direction.NORTHEAST,
    		        Direction.EAST,
    		        Direction.SOUTHEAST,
    		        Direction.SOUTH,
    		        Direction.SOUTHWEST,
    		        Direction.WEST,
    		        Direction.NORTHWEST
    		    };
    		for(Direction dir : directions) {
    			if(rc.senseFlooding(rc.adjacentLocation(dir))) {
        			rc.dropUnit(dir);
        			System.out.println("Dropping unit" + dir);
        			return;
        		}
    		}
    		if(!tryMove(rc.getLocation().directionTo(mapCenter))) {
    			tryMove(randomDirection());
    		}
    		System.out.println("Going to" + mapCenter);
    	}
    }
    
    private static void rotateAroundHQ() throws GameActionException {
    	Direction d = rc.getLocation().directionTo(hqLoc);
    	if(d == Direction.NORTH) {
    		if(!tryMove(Direction.WEST)) {
    			tryMove(randomDirection());
    		}
    	} else if(d==Direction.NORTHEAST) {
    		if(!tryMove(Direction.NORTH)) {
    			tryMove(randomDirection());
    		}
    	} else if (d==Direction.EAST) {
    		if(!tryMove(Direction.NORTH)) {
    			tryMove(randomDirection());
    		}
    	} else if (d==Direction.SOUTHEAST) {
    		if(!tryMove(Direction.EAST)) {
    			tryMove(randomDirection());
    		}
    	} else if (d==Direction.SOUTH) {
    		if(!tryMove(Direction.EAST)) {
    			tryMove(randomDirection());
    		}
    	} else if (d==Direction.SOUTHWEST) {
    		if(!tryMove(Direction.SOUTH)) {
    			tryMove(randomDirection());
    		}
    	} else if (d==Direction.WEST) {
    		if(!tryMove(Direction.SOUTH)) {
    			tryMove(randomDirection());
    		}
    	} else if (d==Direction.NORTHWEST) {
    		if(!tryMove(Direction.WEST)) {
    			tryMove(randomDirection());
    		}
    	}
    }
    
    private static Direction randomDirection() {
    	int rand = random.nextInt(8);
    	if(rand == 0) {
    		return Direction.NORTHWEST;
    	} else if (rand == 1) {
    		return Direction.SOUTHWEST;
    	} else if (rand == 2) {
    		return Direction.WEST;
    	} else if (rand == 3) {
    		return Direction.NORTH;
    	} else if (rand == 4) {
    		return Direction.SOUTH;
    	} else if (rand == 5) {
    		return Direction.EAST;
    	} else if (rand == 6) {
    		return Direction.NORTHEAST;
    	} else {
    		return Direction.SOUTHEAST;
    	}
    }
    
    private static MapLocation findClosestCowOrEnemy() throws GameActionException {
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots();
        for (int i = 0; i < nearbyRobots.length; i++) {         // HQ location must be a neighbor
            if (nearbyRobots[i].getType() == RobotType.COW) {
                return nearbyRobots[i].getLocation();
            } else if (!(nearbyRobots[i].getTeam() == rc.getTeam())) {
             	return nearbyRobots[i].getLocation();
            }
        }
        return null;
    }
    
    private static boolean tryMove(Direction dir) throws GameActionException {
        if (rc.isReady() && rc.canMove(dir)) {
            rc.move(dir);
            return true;
        } else return false;
    }
    /**
     * @author Augusto Savaris
     * @throws GameActionException
     */
    static void runNetGun() throws GameActionException {
        RobotInfo [] info = rc.senseNearbyRobots();
        for(RobotInfo thisInfo : info) {
            int id = thisInfo.ID;
            if(rc.canShootUnit(id) && thisInfo.getTeam() != rcTeam) rc.shootUnit(id);
        }
    }

    //#region Simple actions

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
     * @param dir    The intended direction to build
     * @return  true if robot was built
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
     * Attempt to build a given robot in any direction with a prioritized direction
     * 
     * @param dir    The intended direction of movement
     * @return  true if a move was performed
     * @throws GameActionException
     */
    static boolean tryMoveLoose(Direction dir) throws GameActionException {
        if(!rc.isReady()) return false;

        if(rc.canMove(dir) && rc.canSenseLocation(rcLoc.add(dir)) && !rc.senseFlooding(rcLoc.add(dir)) && rc.senseElevation(rcLoc.add(dir)) > rc.getRoundNum() / 100 + 1) {   // Try to move in a prioritized direction
            rc.move(dir);
            return true;
        }

        Direction leftRotate = dir.rotateLeft();
        dir = dir.rotateRight();

        if(rc.canMove(leftRotate) && rc.canSenseLocation(rcLoc.add(leftRotate)) && !rc.senseFlooding(rcLoc.add(leftRotate)) && rc.senseElevation(rcLoc.add(leftRotate)) > rc.getRoundNum() / 100 + 1) {    // Try 1 CC
            rc.move(leftRotate);
            return true;
        }

        if(rc.canMove(dir) && rc.canSenseLocation(rcLoc.add(dir)) && !rc.senseFlooding(rcLoc.add(dir)) && rc.senseElevation(rcLoc.add(dir)) > rc.getRoundNum() / 100 + 1) {           // Try 1 C
            rc.move(dir);
            return true;
        }

        leftRotate = leftRotate.rotateLeft();
        dir = dir.rotateRight();

        if(rc.canMove(leftRotate) && rc.canSenseLocation(rcLoc.add(leftRotate)) && !rc.senseFlooding(rcLoc.add(leftRotate)) && rc.senseElevation(rcLoc.add(leftRotate)) > rc.getRoundNum() / 100 + 1) {    // Try 2 CC
            rc.move(leftRotate);
            return true;
        }
        if(rc.canMove(dir) && rc.canSenseLocation(rcLoc.add(dir)) && !rc.senseFlooding(rcLoc.add(dir)) && rc.senseElevation(rcLoc.add(dir)) > rc.getRoundNum() / 100 + 1) {           // Try 2 c
            rc.move(dir);
            return true;
        }

        /*leftRotate = leftRotate.rotateLeft();
        dir = dir.rotateRight();
        if(rc.canMove(leftRotate)) {    // Try 3 CC
            rc.move(leftRotate);
            return true;
        }
        if(rc.canMove(dir)) {           // Try 3 c
            rc.move(dir);
            return true;
        }
        dir = dir.rotateRight();
        if(rc.canMove(dir)) {    // Try 4 C / CC
            rc.move(dir);
            return true;
        }*/

        return false;
    }

    //#endregion

    //#region Blockchain
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

    //#endregion

    static void logBytecodes() {
        //System.out.println("\nBC Used:" + Clock.getBytecodeNum() + "\tLeft: " + Clock.getBytecodesLeft());
    }
}
