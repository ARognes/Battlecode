package TangentBug;
import battlecode.common.*;

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
    static Direction moveDir;
    static int consecutiveTurns;
    static boolean clockwise;
    static MapLocation startLoc;
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

                break;
            case MINER:
                RobotInfo[] nearbyRobots = rc.senseNearbyRobots(2, rcTeam);
                for (int i = 0; i < nearbyRobots.length; i++) {         // HQ location must be a neighbor
                    if (nearbyRobots[i].getType() == RobotType.HQ) {
                        hqLoc = nearbyRobots[i].getLocation();
                        soupLoc = null;
                        clockwise = (rc.getLocation().x % 2 == 0) ? true : false;   // Randomize somewhata
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
     * Miner's job is to:
     *      search for soup,
     *      collect and return soup,
     *      communicate with HQ,
     *          report map symmetry features to HQ,
     *          report context-dependent enemy locations to HQ,
     *      communicate with Drones when drones are necessary to get across a barrier
     *      interact with friendly drones properly
     *      zig-zag away and confuse enemy drones
     *      find and collect enemy soup before their miners can,
     *      determine context to build different buildings
     */
    static void runMiner() throws GameActionException {
        int rcRange = rc.getCurrentSensorRadiusSquared();
        MapLocation rcLoc = rc.getLocation();
        //System.out.println("CD: " + rc.getCooldownTurns());
        
        if(rc.isReady()) {  // Cooldown < 1
            int soupAmount = rc.getSoupCarrying();
            if(soupLoc != null && rcLoc.distanceSquaredTo(soupLoc) < rcRange && rc.senseSoup(soupLoc) < 1) soupLoc = null;    // Where did the soup go?


            if(rcState == 2) {                                            // Searching for Refinery?
                rc.setIndicatorDot(hqLoc, 255, 255, 255);
                if(rcLoc.distanceSquaredTo(hqLoc) < 3) {                      // HQ is neighbor?
                    rc.depositSoup(rcLoc.directionTo(hqLoc), soupAmount);           // Deposit soup
                    if(rc.getSoupCarrying() < 1) {
                        System.out.println("Deposited soup!");                      // DEBUG
                        rcState = 0;                                                // Go collect more soup!
                        soupLoc = null;   // Set soup to null unless an array of far away soup in 
                        boundaryFollow = false;
                    }
                }
                else pathfind(rcLoc, hqLoc/*, localMap*/);                      // HQ not neighbor? Keep searching for Refinery
            }
            else if(rcState < 2) {   // Searching for or mining soup?

                boolean wasMining = (rcState == 1) ? true : false;  // SIMPLIFY
                rcState = 0;                                        // Searching for soup
                for (Direction dir : Direction.allDirections())     // Try to mine soup everywhere
                    if(tryMine(dir)) rcState = 1;                   // Mined soup? Stop searching
                
                soupAmount = rc.getSoupCarrying();
                if(rcState == 1) System.out.println("Slurped " + soupAmount + " slurps!"); // DEBUG

                if(wasMining) {
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
                    else soupLoc = null;   // If no more soup, but not full, restart search!
                }

                if(soupLoc != null) pathfind(rcLoc, soupLoc);

                if(rcState == 0 && soupLoc == null) {    // Still searching for soup and haven'd found any?

                    int soupDist = 1000;
                    for(MapLocation thisSoupLoc : rc.senseNearbySoup()) {
                        if(soupLoc == null || rcLoc.distanceSquaredTo(thisSoupLoc) < soupDist) {   // If this soup is closer than closest soup
                            soupLoc = thisSoupLoc;                                             // This soup is closest soup
                            soupDist = rcLoc.distanceSquaredTo(soupLoc);                // Save distance to closest soup for multiple uses
                            if(soupDist < 3) break;                                     // If radius squared is 2 or 1 then it is adjacent, so stop search
                        }
                    }

                    if(soupLoc == null) {                                                              // Wander away from spawn in spiral or search for mirror
                        
                        
                        MapLocation symmetricalLoc = new MapLocation(mapWidth - 1 - hqLoc.x, hqLoc.y);
                        soupLoc = symmetricalLoc;
                        int currDist = rcLoc.distanceSquaredTo(symmetricalLoc);

                        currDist = rcLoc.distanceSquaredTo(symmetricalLoc);
                        symmetricalLoc = new MapLocation(hqLoc.x, mapHeight - 1 - hqLoc.y);
                        int checkDist = rcLoc.distanceSquaredTo(symmetricalLoc);
                        if(checkDist < currDist) {
                            currDist = checkDist;
                            soupLoc = symmetricalLoc;
                        }

                        currDist = rcLoc.distanceSquaredTo(symmetricalLoc);
                        symmetricalLoc = new MapLocation(mapWidth - 1 - hqLoc.x, mapHeight - 1 - hqLoc.y);    // TODO This should be rotational, not xy-flip
                        checkDist = rcLoc.distanceSquaredTo(symmetricalLoc);
                        if(checkDist < currDist) {
                            currDist = checkDist;
                            soupLoc = symmetricalLoc;
                        }
                    }
                }
            }

            if(soupAmount < 1 && rcState != 0) {  // Not carrying soup and not searching for soup? Search for soup!
                rcState = 0;
                soupLoc = null;
            }
        }
        if(soupLoc != null) rc.setIndicatorDot(soupLoc, 255, 255, 255);
    }

    /**
     *  Caste ray from rc to end,
     *  if hit barrier, minimal A* the way out,
     *  if stuck in concave shape, bug it out
     */
    static void pathfind(MapLocation rcLoc, MapLocation endLoc/*, boolean[] localMap*/) throws GameActionException {
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
            rc.setIndicatorDot(rcLoc.add(moveDir), 255, 255, 0);
            
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

            System.out.println(i);

            rc.setIndicatorDot(rcLoc.add(moveDir), 0, 255, 0);
            if(i < 8 && rc.canMove(moveDir)) rc.move(moveDir);

            //System.out.println(">" + moveDir.toString());
            
            if(moveDir.dx != 0 && moveDir.dy != 0) {
                if(i == 0) consecutiveTurns++;
                else consecutiveTurns = 0;

                if(consecutiveTurns < 4) moveDir = clockwise ? moveDir.rotateRight() : moveDir.rotateLeft();
                else {
                    moveDir = clockwise ? moveDir.opposite().rotateLeft() : moveDir.opposite().rotateRight();
                    consecutiveTurns = 0;
                    //clockwise = !clockwise;
                }
            } else consecutiveTurns = 0;

            if(startLoc.x == checkLoc.x && startLoc.y == checkLoc.y) {
                System.out.println("Full Trip! " + startLoc.toString());
                moveDir = Direction.CENTER;
            }

            if(Math.max(Math.abs(startEndX), Math.abs(startEndY)) < dMin) boundaryFollow = false;

            // Find end point with smallest h, store as dLeave
            /*if(!localMap[42]) dLeave = Math.min(Math.max(Math.abs(checkX), Math.abs(checkY)) + Math.max(Math.abs(startEndX - checkX), Math.abs(startEndY - checkY)), dLeave);
            if(!localMap[43]) dLeave = Math.min(Math.max(Math.abs(checkX), Math.abs(checkY)) + Math.max(Math.abs(startEndX - checkX), Math.abs(startEndY - checkY)), dLeave);
            if(!localMap[44]) dLeave = Math.min(Math.max(Math.abs(checkX), Math.abs(checkY)) + Math.max(Math.abs(startEndX - checkX), Math.abs(startEndY - checkY)), dLeave);
            if(!localMap[53]) dLeave = Math.min(Math.max(Math.abs(checkX), Math.abs(checkY)) + Math.max(Math.abs(startEndX - checkX), Math.abs(startEndY - checkY)), dLeave);
            if(!localMap[55]) dLeave = Math.min(Math.max(Math.abs(checkX), Math.abs(checkY)) + Math.max(Math.abs(startEndX - checkX), Math.abs(startEndY - checkY)), dLeave);
            if(!localMap[64]) dLeave = Math.min(Math.max(Math.abs(checkX), Math.abs(checkY)) + Math.max(Math.abs(startEndX - checkX), Math.abs(startEndY - checkY)), dLeave);
            if(!localMap[65]) dLeave = Math.min(Math.max(Math.abs(checkX), Math.abs(checkY)) + Math.max(Math.abs(startEndX - checkX), Math.abs(startEndY - checkY)), dLeave);
            if(!localMap[66]) dLeave = Math.min(Math.max(Math.abs(checkX), Math.abs(checkY)) + Math.max(Math.abs(startEndX - checkX), Math.abs(startEndY - checkY)), dLeave);

            if(dLeave < dMin) {
                boundaryFollow = false;
            }*/

        } else {    // Pathfind
            boolean first = true;   // The optimalDirection should be based off the first movement towards endLoc, not the last. In testing they would jump in water sometimes because of this
            while(checkX * checkX + checkY * checkY < rcRange) {
                Direction d = rcLoc.directionTo(new MapLocation(endLoc.x - checkX, endLoc.y - checkY));
                System.out.println("DIR " + d.toString() + ", " + endLoc.toString());
                checkX += d.dx;
                checkY += d.dy;
                MapLocation checkLoc = rcLoc.translate(checkX, checkY);

                //if(checkX * checkX + checkY * checkY > rcRange) break;    // Ray made it out of range

                rc.setIndicatorDot(checkLoc, 0, 255, 0);
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
                //System.out.println("CHECK DCOST " + dCost);       // DEBUG
                    startEndX = checkX; // Vector from start to collided tile
                    startEndY = checkY;

                    int startEndXRight = checkX;
                    int startEndYRight = checkY;
                    
                    //rc.setIndicatorDot(new MapLocation(rcLoc.x + checkX, rcLoc.y + checkY), 0, 0, 255); // DEBUG

                    int leftDone = 0;   // Finish search if ray castes to end of sensor range
                    int rightDone = 0;
                    System.out.println("HIT!");
    
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

                            System.out.println("Left: " + leftDone + " (" + startEndX + ", " + startEndY + ")");

                            checkX = 0; // Vector used to change update
                            checkY = 0;

                            //System.out.println("Left turn: " + startEndX + " " + startEndY);      // DEBUG

                            while(checkX * checkX + checkY * checkY < rcRange) {    // Check tiles in a ray until a barrier is hit, end is hit, or ray escapes range
                                System.out.println("Left While: " + leftDone);
                                d = rcLoc.directionTo(new MapLocation(rcLoc.x + startEndX, rcLoc.y + startEndY));
                                checkX += d.dx;
                                checkY += d.dy;
                                checkLoc = rcLoc.translate(checkX, checkY);

                                System.out.println(d.toString() + " - " + checkLoc.toString());

                                checkingIndex = 54 + 11 * checkX - checkY; // Convert tile coordinates relative to start to checkingIndex
                                if(checkingIndex > 92) {
                                    if(checkingIndex < 103) checkingIndex--;
                                    else checkingIndex -= 4;
                                } else if(checkingIndex < 16) {
                                    if(checkingIndex > 6) checkingIndex++;
                                    else checkingIndex += 4;
                                }

                                rc.setIndicatorDot(checkLoc, 100, 0, 100);

                                if(rc.canSenseLocation(checkLoc) ? (Math.abs(rc.senseElevation(checkLoc) - rcElev) > 3 || rc.senseFlooding(checkLoc) || rc.isLocationOccupied(checkLoc)) : false) {  // Hit something
                                    System.out.println("Left While HIT: " + (Math.abs(rc.senseElevation(checkLoc) - rcElev)) + ", " + rc.senseFlooding(checkLoc) + ", " + rc.isLocationOccupied(checkLoc));
                                    if(checkX - d.dx + checkY - d.dy != 0) {
                                        System.out.println("PLEASE" + (checkX - d.dx + checkY - d.dy));
                                        int checkCost = Math.max(Math.abs(checkX - d.dx), Math.abs(checkY - d.dy)) + Math.max(Math.abs(endLoc.x - rcLoc.x - (checkX - d.dx)), Math.abs(endLoc.y - rcLoc.y - (checkY - d.dy)));
                                        System.out.println("$$: " + checkCost);
                                        if(checkCost <= optimalCost) {
                                            optimalCost = checkCost;
                                            optimalDir = rcLoc.directionTo(new MapLocation(checkX - d.dx + rcLoc.x, checkY - d.dy + rcLoc.y));
                                        }
                                    }

                                    break;
                                }

                                if(checkX == startEndX && checkY == startEndY) {    // Found offset end
                                    System.out.println("Left While END!: " + leftDone);
                                    int checkCost = Math.max(Math.abs(checkX), Math.abs(checkY)) + Math.max(Math.abs(endLoc.x - rcLoc.x - checkX), Math.abs(endLoc.y - rcLoc.y - checkY));
                                    //System.out.println("$$$: " + checkCost);
                                    if(checkCost <= optimalCost) {
                                        optimalCost = checkCost;
                                        optimalDir = rcLoc.directionTo(new MapLocation(checkX + rcLoc.x, checkY + rcLoc.y));
                                    }
                                    leftDone = 10;
                                    //rightDone = 10;
                                    break;
                                }
                                //System.out.println("SE: (" + startEndX + ", " + startEndY + ") | (" + checkX + ", " + checkY + ")" + "Atan: " + Math.atan2(startEndY - endLoc.y, startEndX - endLoc.x));
                            }
                            leftDone++;
                        }
                    
                        if(rightDone < 6) { 
                            System.out.println("Right: " + rightDone);
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
                                System.out.println("Right while: " + rightDone);
                                d = rcLoc.directionTo(new MapLocation(rcLoc.x + startEndXRight, rcLoc.y + startEndYRight));
                                checkX += d.dx;
                                checkY += d.dy;
                                checkLoc = rcLoc.translate(checkX, checkY);

                                System.out.println(d.toString() + " - " + checkLoc.toString());

                                checkingIndex = 54 + 11 * checkX - checkY; // Convert tile coordinates relative to start to checkingIndex
                                if(checkingIndex > 92) {
                                    if(checkingIndex < 103) checkingIndex--;
                                    else checkingIndex -= 4;
                                } else if(checkingIndex < 16) {
                                    if(checkingIndex > 6) checkingIndex++;
                                    else checkingIndex += 4;
                                }

                                rc.setIndicatorDot(checkLoc, 255, 0, 255);
                                //else rc.setIndicatorDot(new MapLocation(rcLoc.x + checkX, rcLoc.y + checkY), 255 - 64 * j, 0, 0);

                                if(rc.canSenseLocation(checkLoc) ? (Math.abs(rc.senseElevation(checkLoc) - rcElev) > 3 || rc.senseFlooding(checkLoc) || rc.isLocationOccupied(checkLoc)) : false) {  // Hit something
                                    System.out.println("Right while HIT: " + rightDone);
                                    if(checkX - d.dx + checkY - d.dy != 0) {
                                        int checkCost = Math.max(Math.abs(checkX - d.dx), Math.abs(checkY - d.dy)) + Math.max(Math.abs(endLoc.x - rcLoc.x - (checkX - d.dx)), Math.abs(endLoc.y - rcLoc.y - (checkY - d.dy)));
                                        //System.out.println("$$: " + checkCost);
                                        if(checkCost <= optimalCost) {
                                            optimalCost = checkCost;
                                            optimalDir = rcLoc.directionTo(new MapLocation(checkX - d.dx + rcLoc.x, checkY - d.dy + rcLoc.y));
                                        }
                                    }
                                    break;
                                }

                                if(checkX == startEndXRight && checkY == startEndYRight) {
                                    System.out.println("Right while END!: " + rightDone);
                                    int checkCost = Math.max(Math.abs(checkX), Math.abs(checkY)) + Math.max(Math.abs(endLoc.x - rcLoc.x - checkX), Math.abs(endLoc.y - rcLoc.y - checkY));
                                    //System.out.println("$$$: " + checkCost);
                                    if(checkCost <= optimalCost) {
                                        optimalCost = checkCost;
                                        optimalDir = rcLoc.directionTo(new MapLocation(checkX + rcLoc.x, checkY + rcLoc.y));
                                    }
                                    //leftDone = 10;
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
                moveDir = rcLoc.directionTo(endLoc);

                moveDir = clockwise ? moveDir.rotateLeft().rotateLeft() : moveDir.rotateRight().rotateRight();    // Arbitrarily search clockwise, optimize this later
                //clockwise = true;

                startLoc = rcLoc;

                dMin = optimalCost;
                System.out.println("boundaryFollow: " + optimalCost);
            } //else dMin = optimalCost;
            System.out.println("DONE! " + optimalDir.toString());
            if(rc.canMove(optimalDir)) rc.move(optimalDir);       // Check if can move, just to avoid any possible errors that haven't been accounted for
            startLoc = rcLoc.add(optimalDir);
        }
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
