package MinerPathfinding;
import battlecode.common.*;

/**
 * @author Austin Rognes
 **/

/**
 * Implemented the Tangent Bug from https://www.cs.cmu.edu/~motionplanning/lecture/Chap2-Bug-Alg_howie.pdf
 * UML diagram: http://4.bp.blogspot.com/-OxVSk7HwsnM/Umrb8H9bvrI/AAAAAAAAADY/7TceTdkxe_E/s1600/4.png
 * Moves towards target, if senses barrier, move towards shortest possible detour,
 * if reach dead end, follow contour until closer than when dead end was found.
 **/

public strictfp class RobotPlayer {
    static RobotController rc;

    // Well hello there traveller! Welcome to static variable hell! How did you get here you may ask?
    // Well, your team member ARognes was creating copious amounts of variables that needed to survive multiple turns,
    // thus he invoked static variable hell through Satan!

    static int step;        // Used my most Robots
    static MapLocation hqLoc;
    static RobotType rcType;
    static Team rcTeam;
    static int rcState;
    static int mapWidth;
    static int mapHeight;

    static Direction nextFrameSpawnDir; // Used by HQ

    static MapLocation soupLoc;  // Used by Miner

    static boolean boundaryFollow;  // Used by pathfinding
    static int dLeave;
    static int dMin;


    /**
     * run() is the method that is called when a robot is instantiated in the Battlecode world.
     * If this method returns, the robot dies!
     **/
    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {

        // This is the RobotController object. You use it to perform actions from this robot,
        // and to get information on its current status.
        RobotPlayer.rc = rc;
        rcType = rc.getType();
        rcTeam = rc.getTeam();
        rcState = 0;
        step = 0;
        mapWidth = rc.getMapWidth();
        mapHeight = rc.getMapHeight();
        boundaryFollow = false;

        // Find and save hq location
        switch(rcType) {
            case HQ: 
                hqLoc = rc.getLocation();
                nextFrameSpawnDir = null;
                dMin = 0;

                break;
            case MINER:
                RobotInfo[] nearbyRobots = rc.senseNearbyRobots(2, rcTeam);
                for (int i = 0; i < nearbyRobots.length; i++) {         // HQ location must be a neighbor
                    if (nearbyRobots[i].getType() == RobotType.HQ) {
                        hqLoc = nearbyRobots[i].getLocation();
                        soupLoc = null;
                        break;
                    }
                }
                break;
        }

        //System.out.println(rcType + " created. Found hq at  "  + hqLoc);
        while (true) {
            step += 1;
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

    static void callTest() {
        return;
    }

    /**
     * Simple miner spawn
     */
    static void runHQ() throws GameActionException {
        switch(step) {
            case 1:
                
                // Top priority: Find closest soup. Soup location relative to HQ position
                MapLocation soupLoc = null;
                int rcRange = rc.getCurrentSensorRadiusSquared();
                
                // Check all tiles of radius or less
                for(int i = -7; i < 8; i++) {
                    for(int j = -7; j < 8; j++) {
                        MapLocation l = new MapLocation(hqLoc.x + i, hqLoc.y + j);
                        if(l.x < 0 || l.x >= mapWidth) break;
                        if(l.y < 0 || l.y >= mapHeight || i * i + j * j >= rcRange) continue;

                        // Find closest soup, simple distance without pathfinding as miner will do that
                        if(rc.senseSoup(l) > 0 && (soupLoc == null || i * i + j * j < soupLoc.x * soupLoc.x + soupLoc.y * soupLoc.y)) soupLoc = new MapLocation(i, j);
                        rc.setIndicatorDot(new MapLocation(hqLoc.x + i, hqLoc.y + j), 0, 0, 255);
                    }
                }

                // Find that soup!
                if(soupLoc != null) {
                    //System.out.println("HQ smells SOUP! " + soupLoc);
                    
                    if(soupLoc.y > 0) {
                        if(soupLoc.y > soupLoc.x) {
                            nextFrameSpawnDir = Direction.NORTHWEST;
                            tryBuild(RobotType.MINER, Direction.NORTH);
                        } else {
                            nextFrameSpawnDir = Direction.NORTHEAST;
                            tryBuild(RobotType.MINER, Direction.EAST);
                        }
                    } else {
                        if(soupLoc.y >= soupLoc.x) {
                            nextFrameSpawnDir = Direction.SOUTHWEST;
                            tryBuild(RobotType.MINER, Direction.WEST);
                        } else {
                            nextFrameSpawnDir = Direction.SOUTHEAST;
                            tryBuild(RobotType.MINER, Direction.SOUTH);
                        }
                    }
                    rc.setIndicatorDot(new MapLocation(hqLoc.x + soupLoc.x, hqLoc.y + soupLoc.y), 255, 255, 255);
                    break;
                } 
                else {    // Send miners NorthWest and SouthEast so they move diagonally and search 16 new tiles rather than 11
                    nextFrameSpawnDir = Direction.NORTHWEST;
                    tryBuild(RobotType.MINER, Direction.SOUTHEAST);
                }

                break;
            case 2: if(nextFrameSpawnDir != null) tryBuild(RobotType.MINER, nextFrameSpawnDir); break;
        }
    }

    /**
     * 
     */
    static void runMiner() throws GameActionException {
        int rcRange = rc.getCurrentSensorRadiusSquared();
        MapLocation rcLoc = rc.getLocation();
        RobotInfo[] senseRobots = rc.senseNearbyRobots();   // 100 bytecodes!
        boolean[] localMap = new boolean[109];                                              // boolean map of moveable locations within radius
        for(int i=0; i<senseRobots.length; i++) {                                           // Mark every localMap element with a Robot as true
            MapLocation robot = senseRobots[i].getLocation().translate(-rcLoc.x, -rcLoc.y); // Get Robot position relative to Miner
            int localIndex = 54 +  11 * robot.x - robot.y;                                  // Transform x and y coordinates relative to Miner, to Miner radius index coordinates
            switch(robot.x) {
                case -5: localIndex += 4; break;
                case -4: localIndex++; break;
                case 4: localIndex--; break;
                case 5: localIndex -= 4; break;
            }
            localMap[localIndex] = true;
            rc.setIndicatorDot(robot.translate(rcLoc.x, rcLoc.y), 255, 0, 0);   // DEBUG This can be off by one if this robot senser before another robot moves
        }

        int rcElev = rc.senseElevation(rcLoc);
        MapLocation globLoc;
        for(int i=0; i<localMap.length; i++) {  // Mark every localMap element flooded or elevation blocked as true
            if(localMap[i]) continue;

            //globLoc;  // Convert localMap index coordinate to global location

            switch(i) {                 // Offset for sense range's circle curves
                case 0: case 1: case 2: case 3: case 4: case 5: case 6:
                    globLoc = new MapLocation(-5, i - 3);
                    break;
                case 7: case 8: case 9: case 10: case 11: case 12: case 13: case 14: case 15:
                    globLoc = new MapLocation(-4, i - 11);
                    break;
                case 93: case 94: case 95: case 96: case 97: case 98: case 99: case 100: case 101:
                    globLoc = new MapLocation(4, i - 97);
                    break;
                case 102: case 103: case 104: case 105: case 106: case 107: case 108:
                    globLoc = new MapLocation(5, i - 105);
                    break;
                default:
                    globLoc = new MapLocation((i-5) / 11 - 4, 5 - (i-5) % 11);
            }

            if(globLoc.x * globLoc.x + globLoc.y * globLoc.y > rcRange) continue;
            globLoc = globLoc.translate(rcLoc.x, rcLoc.y);
            if(globLoc.x < 0 || globLoc.x >= mapWidth || globLoc.y < 0 || globLoc.y >= mapHeight) continue;

            //System.out.println(globLoc.toString() + ", " + rcRange);
            if(rc.senseFlooding(globLoc)) {
                localMap[i] = true;
                rc.setIndicatorDot(globLoc, 0, 255, 255);
                //System.out.println("Water! " + globLoc.toString());
            }
            else if(Math.abs(rc.senseElevation(globLoc) - rcElev) > 3) localMap[i] = true;
        }
        
        if(rc.isReady()) {  // Cooldown < 1
            int soupAmount = rc.getSoupCarrying();
            if(soupLoc != null && rcLoc.distanceSquaredTo(soupLoc) < rcRange && rc.senseSoup(soupLoc) < 1) soupLoc = null;    // Where did the soup go?


            if(rcState == 2) {                                            // Searching for Refinery?
                rc.setIndicatorDot(hqLoc, 255, 255, 255);
                if(rcLoc.distanceSquaredTo(hqLoc) < 3) {                      // HQ is neighbor?
                    rc.depositSoup(rcLoc.directionTo(hqLoc), soupAmount);           // Deposit soup
                    if(rc.getSoupCarrying() < 1) {
                        System.out.println("Deposited soup!");                      // DEBUG
                        rcState = 0;                                                            // Go collect more soup!
                        soupLoc = null;
                    }
                }
                else pathfind(rcLoc, hqLoc, localMap);                      // HQ not neighbor? Keep searching for Refinery
            }
            else if(rcState < 2) {   // Searching for or mining soup?

                boolean wasMining = (rcState == 1) ? true : false;  // SIMPLIFY
                rcState = 0;                                        // Searching for soup
                for (Direction dir : Direction.allDirections())     // Try to mine soup everywhere
                    if(tryMine(dir)) rcState = 1;                   // Mined soup? Stop searching
                
                soupAmount = rc.getSoupCarrying();
                //if(rcState == 1) System.out.println("Slurped " + soupAmount + " slurps!"); // DEBUG
                if(soupAmount == RobotType.MINER.soupLimit) rcState = 2;   // Full? Go search for Refinery
                else if(rcState == 0 && wasMining) soupLoc = null;   // If no more soup, but not full, restart search!
                else if(soupLoc != null) pathfind(rcLoc, soupLoc, localMap);  // soupLoc is not relative here

                if(rcState == 0 && soupLoc == null) {    // Still searching for soup and haven'd found any?
                    for(int i = -5; i < 6; i++) {   // Check all tiles of radius for soup!
                        //rcRange = rc.getCurrentSensorRadiusSquared();
                        for(int j= -5; j < 6; j++) {
                            MapLocation l = new MapLocation(rcLoc.x + i, rcLoc.y + j);
                            if(l.x < 0 || l.x >= mapWidth) break;
                            if(l.y < 0 || l.y >= mapHeight || i * i + j * j >= rc.getCurrentSensorRadiusSquared()) continue;

                            // Find closest soup!
                            if(rc.senseSoup(l) > 0 && (soupLoc == null || i * i + j * j < soupLoc.x * soupLoc.x + soupLoc.y * soupLoc.y)) soupLoc = new MapLocation(i, j);

                            // TODO Add all soup to Array or ArrayList
                        }
                    }

                    if(soupLoc != null) {                                               // Found soup?
                        //System.out.println("I smell SOUP! " + soupLoc);                   // DEBUG
                        //distInit = soupLoc.x * soupLoc.x + soupLoc.y * soupLoc.y;           // Record initial distance from soup
                        soupLoc = soupLoc.translate(rcLoc.x, rcLoc.y);  // Soup location was relative to Miner location, offset by Miner location
                        rc.setIndicatorDot(soupLoc, 255, 255, 255);
                        //System.out.println("FOUND SOUP!");
                    }
                    else {                                                              // Wander away from spawn in spiral
                        if(rc.canMove(Direction.NORTH)) rc.move(Direction.NORTH);
                        rc.setIndicatorDot(rcLoc, 255, 255, 255);
                        //if() System.out.println("I am wandering!");
                        //else moveDir = Direction.allDirections()[random.nextInt(8)];
                    }
                }
            }

            if(soupAmount < 1 && rcState != 0) {  // Not carrying soup and not searching for soup? Search for soup!
                rcState = 0;
                soupLoc = null;
            }
        }
    }

    /**
     *  Caste ray from rc to end,
     *  if hit barrier, minimal A* the way out,
     *  if stuck in concave shape, bug it out
     */
    static void pathfind(MapLocation rcLoc, MapLocation endLoc, boolean[] localMap) throws GameActionException {
        if(!rc.isReady()) return;
        int rcRange = rc.getCurrentSensorRadiusSquared();

        int startEndX = endLoc.x - rcLoc.x;                 // Vector from start to end
        int startEndY = endLoc.y - rcLoc.y;
        int distInt = (int)Math.max(Math.abs(startEndX), Math.abs(startEndY));  // Min amount of moves needed to get from start to end

        int checkX = 0; // Vector from start to checking tile
        int checkY = 0;
        int checkingIndex; // Index of checking tile in localMap

        Direction previousDir = rcLoc.directionTo(endLoc);  // Direction from start to end

        int previousCost = distInt;                         // Heuristic: cost of moving from current position to end, COULD be further than sense range!

        Direction costDir = previousDir;    // Save direction before first collision
        int cost = previousCost;            // Save cost of tile before first collision

        if(boundaryFollow) {    // Follow boundary out of concave shape


            // Find end point with smallest h, store as dLeave
            if(!localMap[42]) dLeave = Math.min(Math.max(Math.abs(checkX), Math.abs(checkY)) + Math.max(Math.abs(startEndX - checkX), Math.abs(startEndY - checkY)), dLeave);
            if(!localMap[43]) dLeave = Math.min(Math.max(Math.abs(checkX), Math.abs(checkY)) + Math.max(Math.abs(startEndX - checkX), Math.abs(startEndY - checkY)), dLeave);
            if(!localMap[44]) dLeave = Math.min(Math.max(Math.abs(checkX), Math.abs(checkY)) + Math.max(Math.abs(startEndX - checkX), Math.abs(startEndY - checkY)), dLeave);
            if(!localMap[53]) dLeave = Math.min(Math.max(Math.abs(checkX), Math.abs(checkY)) + Math.max(Math.abs(startEndX - checkX), Math.abs(startEndY - checkY)), dLeave);
            if(!localMap[55]) dLeave = Math.min(Math.max(Math.abs(checkX), Math.abs(checkY)) + Math.max(Math.abs(startEndX - checkX), Math.abs(startEndY - checkY)), dLeave);
            if(!localMap[64]) dLeave = Math.min(Math.max(Math.abs(checkX), Math.abs(checkY)) + Math.max(Math.abs(startEndX - checkX), Math.abs(startEndY - checkY)), dLeave);
            if(!localMap[65]) dLeave = Math.min(Math.max(Math.abs(checkX), Math.abs(checkY)) + Math.max(Math.abs(startEndX - checkX), Math.abs(startEndY - checkY)), dLeave);
            if(!localMap[66]) dLeave = Math.min(Math.max(Math.abs(checkX), Math.abs(checkY)) + Math.max(Math.abs(startEndX - checkX), Math.abs(startEndY - checkY)), dLeave);

            if(dLeave < dMin) boundaryFollow = false;

        } else {    // Pathfind
            for(int i=1; i<=distInt; i++) { // Search tiles in ray towards endLoc
                Direction d = rcLoc.directionTo(new MapLocation(endLoc.x - checkX, endLoc.y - checkY));
                checkX += d.dx;
                checkY += d.dy;

                if(checkX * checkX + checkY * checkY > rcRange || (rcLoc.x + checkX == endLoc.x && rcLoc.y + checkY == endLoc.y)) break;    // Ray made it out of range or hit the end
                //System.out.println("Test " + d.dx + ", " + d.dy); // DEBUG

                checkingIndex = 54 + 11 * checkX - checkY;     // Convert tile coordinates relative to start to checkingIndex
                if(checkingIndex > 92) {
                    if(checkingIndex < 103) checkingIndex--;
                    else checkingIndex -= 4;
                } else if(checkingIndex < 16) {
                    if(checkingIndex > 6) checkingIndex++;
                    else checkingIndex += 4; 
                }

                //rc.setIndicatorDot(new MapLocation(rcLoc.x + checkX, rcLoc.y + checkY), 0, 255 - i * 64, 0);    // DEBUG

                if(localMap[checkingIndex]) {  // Hit something

                    startEndX = checkX; // Vector from start to collided tile
                    startEndY = checkY;
                    
                    //rc.setIndicatorDot(new MapLocation(rcLoc.x + checkX, rcLoc.y + checkY), 0, 0, 255); // DEBUG

                    int leftDone = 0;   // Finish search if ray castes to end of sensor range
                    int rightDone = 0;
                    do {

                        if(leftDone < 3) { 
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

                            //System.out.println("Left turn: " + startEndX + " " + startEndY);

                            for(int j=1; j<=distInt; j++) {   // Search for barrier in new left ray
                                d = new MapLocation(0, 0).directionTo(new MapLocation(startEndX - checkX, startEndY - checkY));
                                checkX += d.dx;
                                checkY += d.dy;

                                if(checkX * checkX + checkY * checkY >= rcRange) break;
                                //System.out.println(d.dx + ", " + d.dy + " | " + checkX + ", " + checkY + "  |  " + startEndX + ", " + startEndY);

                                checkingIndex = 54 + 11 * checkX - checkY; // Convert tile coordinates relative to start to checkingIndex
                                if(checkingIndex > 92) {
                                    if(checkingIndex < 103) checkingIndex--;
                                    else checkingIndex -= 4;
                                } else if(checkingIndex < 16) {
                                    if(checkingIndex > 6) checkingIndex++;
                                    else checkingIndex += 4;
                                }

                                //if(localMap[checkingIndex]) rc.setIndicatorDot(new MapLocation(rcLoc.x + checkX, rcLoc.y + checkY), 0, 0, 255);
                                //else rc.setIndicatorDot(new MapLocation(rcLoc.x + checkX, rcLoc.y + checkY), 255 - 64 * j, 0, 0);

                                previousDir = rcLoc.directionTo(new MapLocation(checkX + rcLoc.x, checkY + rcLoc.y));   // Save direction from start to this tile, in case barrier is hit next iteration

                                if(localMap[checkingIndex]) {  // If hit wall check last tile heuristic
                                    if(previousCost < cost) {
                                        cost = previousCost;
                                        costDir = previousDir;
                                    }
                                    break;
                                }

                                previousCost = Math.max(Math.abs(checkX), Math.abs(checkY)) + Math.max(Math.abs(startEndX - checkX), Math.abs(startEndY - checkY)); // Save cost of this tile, in case a barrier is hit next iteration

                                if(checkX == startEndX && checkY == startEndY) {
                                    if(previousCost < cost) {
                                        cost = previousCost;
                                        costDir = previousDir;
                                    }
                                }
                                //System.out.println("SE: (" + startEndX + ", " + startEndY + ") | (" + checkX + ", " + checkY + ")" + "Atan: " + Math.atan2(startEndY - endLoc.y, startEndX - endLoc.x));
                                leftDone++;
                                if(j == distInt-1) {    // If any ray reaches end of range, search is over for left side
                                    leftDone = 10;
                                    break;
                                }
                            }
                        }
                    
                        if(rightDone < 3) { 
                            if(startEndY > 0) {     // Vector from start to open space to the RIGHT of collided tile
                                if(startEndX > 0) startEndY--;
                                else startEndX++;
                            } else if(startEndY < 0) {
                                if(startEndX >= 0) startEndX--;
                                else startEndY++;
                            } else if(startEndX > 0) startEndY--;
                            else startEndY++;

                            checkX = 0; // Vector used to change update
                            checkY = 0;

                            for(int j=1; j<=distInt; j++) {   // Search for barrier in new left ray
                                d = new MapLocation(0, 0).directionTo(new MapLocation(startEndX - checkX, startEndY - checkY));
                                checkX += d.dx;
                                checkY += d.dy;

                                if(checkX * checkX + checkY * checkY >= rcRange) break;
                                //System.out.println(d.dx + ", " + d.dy + " | " + checkX + ", " + checkY + "  |  " + startEndX + ", " + startEndY);

                                checkingIndex = 54 + 11 * checkX - checkY; // Convert tile coordinates relative to start to checkingIndex
                                if(checkingIndex > 92) {
                                    if(checkingIndex < 103) checkingIndex--;
                                    else checkingIndex -= 4;
                                } else if(checkingIndex < 16) {
                                    if(checkingIndex > 6) checkingIndex++;
                                    else checkingIndex += 4;
                                }

                                //if(localMap[checkingIndex]) rc.setIndicatorDot(new MapLocation(rcLoc.x + checkX, rcLoc.y + checkY), 0, 0, 255);
                                //else rc.setIndicatorDot(new MapLocation(rcLoc.x + checkX, rcLoc.y + checkY), 255 - 64 * j, 0, 0);

                                previousDir = rcLoc.directionTo(new MapLocation(checkX + rcLoc.x, checkY + rcLoc.y));   // Save direction from start to this tile, in case barrier is hit next iteration

                                if(localMap[checkingIndex]) {  // If hit wall check last tile heuristic
                                    if(previousCost < cost) {
                                        cost = previousCost;
                                        costDir = previousDir;
                                    }
                                    break;
                                }

                                previousCost = Math.max(Math.abs(checkX), Math.abs(checkY)) + Math.max(Math.abs(startEndX - checkX), Math.abs(startEndY - checkY)); // Save cost of this tile, in case a barrier is hit next iteration

                                if(checkX == startEndX && checkY == startEndY) {
                                    if(previousCost < cost) {
                                        cost = previousCost;
                                        costDir = previousDir;
                                    }
                                }
                                //System.out.println("SE: (" + startEndX + ", " + startEndY + ") | (" + checkX + ", " + checkY + ")" + "Atan: " + Math.atan2(startEndY - endLoc.y, startEndX - endLoc.x));
                                rightDone++;
                                if(j == distInt-1) {    // If any ray reaches end of range, search is over for left side
                                    rightDone = 10;
                                    break;
                                }
                            }
                        }
                        
                    } while(leftDone < 3 || rightDone < 3);
                    previousCost = cost;
                    previousDir = costDir;
                    break;
                }

                // Cost = dist from start to tile + dist from tile to end
                previousCost = Math.max(Math.abs(checkX), Math.abs(checkY)) + Math.max(Math.abs(startEndX - checkX), Math.abs(startEndY - checkY));
            }
            /*if(dMin == previousCost) {  // Stuck in concave shape
                boundaryFollow = true;
                System.out.println("boundaryFollow");
            } else dMin = previousCost;*/
            if(rc.canMove(previousDir)) rc.move(previousDir);       // Avoid random errors
        }

        
        /*
        if(boundaryFollow) {
            // Follow boundary

            // Find end point with smallest h, store as dLeave

            if(dLeave < dMin) boundaryFollow = false;

        } else {
            if(step == 0) { // if object in way

                // Calculate heuristics

                // Move to point with min h

                //if(lowest Heuristic > last lowest Heuristic) {
                    boundaryFollow = true;
                    // Get lowest h tile, store as dMin

                //}
            } else {
                // move towards endLoc
            }
        }*/

        // Update current position

    }





    static void runRefinery() throws GameActionException {

    }

    static void runVaporator() throws GameActionException {

    }

    static void runDesignSchool() throws GameActionException {

    }

    static void runFulfillmentCenter() throws GameActionException {
        for (Direction dir : Direction.allDirections())
            if(tryBuild(RobotType.DELIVERY_DRONE, dir)) break;
    }

    static void runLandscaper() throws GameActionException {

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
     * Attempts to mine soup in a given direction.
     *
     * @param dir The intended direction of mining
     * @return true if a move was performed
     * @throws GameActionException
     */
    static boolean tryMine(Direction dir) throws GameActionException {
        if (rc.isReady() && rc.canMineSoup(dir)) {
            rc.mineSoup(dir);
            return true;
        } else return false;
    }

    /**
     * Attempts to refine soup in a given direction.
     *
     * @param dir The intended direction of refining
     * @return true if a move was performed
     * @throws GameActionException
     */
    static boolean tryRefine(Direction dir) throws GameActionException {
        if (rc.isReady() && rc.canDepositSoup(dir)) {
            rc.depositSoup(dir, rc.getSoupCarrying());
            return true;
        } else return false;
    }


    static void tryBlockchain() throws GameActionException {
        if (step < 3) {
            int[] message = new int[7];
            for (int i = 0; i < 7; i++) {
                message[i] = 123;
            }
            if (rc.canSubmitTransaction(message, 10))
                rc.submitTransaction(message, 10);
        }
        // System.out.println(rc.getRoundMessages(step-1));
    }

    static void logBytecodes() {
        System.out.println("\nBC Used:" + Clock.getBytecodeNum() + "\tLeft: " + Clock.getBytecodesLeft());
    }
}
