package communication;
import battlecode.common.Clock;
import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.GameConstants;
import battlecode.common.MapLocation;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.RobotType;
import battlecode.common.Team;
import battlecode.common.Transaction;

public strictfp class RobotPlayer {
    static RobotController rc;
    static MapLocation hqLocation;
    static MapLocation soupLocation;
    static int minersQuant = 0;
    //static Direction direction;
    static int highestBid = 1, lowestBid = 1;
    static int key1index = 3, key2index = 5;
    static int key1, key2;

    static Direction[] directions = {
        Direction.NORTH,
        Direction.NORTHEAST,
        Direction.EAST,
        Direction.SOUTHEAST,
        Direction.SOUTH,
        Direction.SOUTHWEST,
        Direction.WEST,
        Direction.NORTHWEST
    };
    static RobotType[] spawnedByMiner = {RobotType.REFINERY, RobotType.VAPORATOR, RobotType.DESIGN_SCHOOL,
            RobotType.FULFILLMENT_CENTER, RobotType.NET_GUN};

    static int turnCount;

    /**
     * run() is the method that is called when a robot is instantiated in the Battlecode world.
     * If this method returns, the robot dies!
     **/
    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {

        // This is the RobotController object. You use it to perform actions from this robot,
        // and to get information on its current status.
        RobotPlayer.rc = rc;

        turnCount = 0;

        System.out.println("I'm a " + rc.getType() + " and I just got created!");
        while (true) {
            turnCount += 1;
            // Try/catch blocks stop unhandled exceptions, which cause your robot to explode
            try {
                // Here, we've separated the controls into a different method for each RobotType.
                // You can add the missing ones or rewrite this into your own control structure.
                System.out.println("I'm a " + rc.getType() + "! Location " + rc.getLocation());
                switch (rc.getType()) {
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
                System.out.println(rc.getType() + " Exception");
                e.printStackTrace();
            }
        }
    }

    static void runHQ() throws GameActionException {
    	MapLocation [] soupLoc = rc.senseNearbySoup();
    	key1 = rc.getID();
    	key2 = 2*key1;
    	if(soupLoc != null) {
    		int [] message = {soupLoc[0].x, soupLoc[0].y,0,key1,0,key2,0};
    		lowPriorityBlockchain(message);
    	}
    	if(minersQuant < 1) {
    		for (Direction dir : directions) {
    			tryBuild(RobotType.MINER, dir);
    			minersQuant++;
    		}
    	}
    	Transaction [] in = rc.getBlock(rc.getRoundNum()-1);
    	for(int i = 0; i < in.length; i++) {
    		int [] message = in[i].getMessage();
    		if(message[key2index]/message[key1index] == 2) {
    			tryBuild(RobotType.MINER, Direction.SOUTH);
    		}
    	}
    }

    static void runMiner() throws GameActionException {
        //tryBlockchain();
    	key1 = rc.getID();
    	key2 = 2*key1;
    	Transaction [] in = rc.getBlock(rc.getRoundNum()-1);
    	highestBid = getHighestBid(in);
    	lowestBid = getLowestBid(in);
    	Direction direction = randomDirection();
    	
    	
    	for(int i = 0; i < in.length; i++) {
    		int [] message = in[i].getMessage();
    		if(message[key2index]/message[key1index] == 2) {
    			soupLocation = new MapLocation(message[0], message[1]);
    			direction = rc.getLocation().directionTo(soupLocation);
    		}
    	}
    	
    	int [] message = {0,0,0,key1,0,key2,0};
    	System.out.println(direction);
        tryMove(direction);
        //tryBuild(randomSpawnedByMiner(), randomDirection());
        //for (Direction dir : directions)
            //tryBuild(RobotType.FULFILLMENT_CENTER, dir);
        for (Direction dir : directions) {
        	if (tryRefine(dir)) {
        		System.out.println("I refined soup! " + rc.getTeamSoup());
        	}
        }        
        for (Direction dir : directions) {
        	if (tryMine(dir)) {
        		System.out.println("I mined soup! " + rc.getSoupCarrying());
        		message[0] = rc.getLocation().x;
        		message[1] = rc.getLocation().y;
        	}
        }
        lowPriorityBlockchain(message);
    }

    static void runRefinery() throws GameActionException {
        // System.out.println("Pollution: " + rc.sensePollution(rc.getLocation()));
    }

    static void runVaporator() throws GameActionException {

    }

    static void runDesignSchool() throws GameActionException {

    }

    static void runFulfillmentCenter() throws GameActionException {
        for (Direction dir : directions)
            tryBuild(RobotType.DELIVERY_DRONE, dir);
    }

    static void runLandscaper() throws GameActionException {

    }

    static void runDeliveryDrone() throws GameActionException {
    	RobotInfo [] hqLoc = rc.senseNearbyRobots(GameConstants.DELIVERY_DRONE_PICKUP_RADIUS_SQUARED, rc.getTeam());
    	for(int i = 0; i < hqLoc.length; i++) {
    		if(hqLoc[i].getType()==RobotType.HQ) {
    			hqLocation = hqLoc[i].getLocation();
    		}
    	}
        Team enemy = rc.getTeam().opponent();
        if (!rc.isCurrentlyHoldingUnit()) {
            // See if there are any enemy robots within capturing range
            RobotInfo[] robots = rc.senseNearbyRobots(GameConstants.DELIVERY_DRONE_PICKUP_RADIUS_SQUARED, enemy);
            if (robots.length > 0) {
                // Pick up a first robot within range
                rc.pickUpUnit(robots[0].getID());
                System.out.println("I picked up " + robots[0].getID() + "!");
            }
        } else {
            // No close robots, so wait on top of hq or move to hq
        	if(rc.isReady()) { // cooldown > 1.5
        		if(rc.getLocation() != hqLocation) { // if robot is not on hq, go to hq
            		Direction dir = rc.getLocation().directionTo(hqLocation);
            		rc.move(dir);
            	}
        	}
        }
    }

    static void runNetGun() throws GameActionException {

    }

    /**
     * Returns a random Direction.
     *
     * @return a random Direction
     */
    static Direction randomDirection() {
        return directions[(int) (Math.random() * directions.length)];
    }

    /**
     * Returns a random RobotType spawned by miners.
     *
     * @return a random RobotType
     */
    static RobotType randomSpawnedByMiner() {
        return spawnedByMiner[(int) (Math.random() * spawnedByMiner.length)];
    }

    static boolean tryMove() throws GameActionException {
        for (Direction dir : directions)
            if (tryMove(dir))
                return true;
        return false;
    }

    /**
     * Attempts to move in a given direction.
     *
     * @param dir The intended direction of movement
     * @return true if a move was performed
     * @throws GameActionException
     */
    static boolean tryMove(Direction dir) throws GameActionException {
        // System.out.println("I am trying to move " + dir + "; " + rc.isReady() + " " + rc.getCooldownTurns() + " " + rc.canMove(dir));
        if (rc.isReady() && rc.canMove(dir)) {
            rc.move(dir);
            return true;
        } else return false;
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

    static void highPriorityBlockchain(int [] message) throws GameActionException {
    	if(message[key2index]/message[key1index] != 2) {
    		highestBid++;
    	}
        if (rc.canSubmitTransaction(message, highestBid)) {
            rc.submitTransaction(message, highestBid);
            System.out.println(highestBid);
        }
        System.out.println(highestBid + "was highest bid");
    }
    
    static void lowPriorityBlockchain(int [] message) throws GameActionException {
    	if(message[key2index]/message[key1index] != 2) {
    		lowestBid ++;
    	}
        if (rc.canSubmitTransaction(message, lowestBid)) {
            rc.submitTransaction(message, lowestBid);
            System.out.println(lowestBid);
         }
         System.out.println(lowestBid + "was lowest bid");
    }
    
    static int getHighestBid(Transaction [] in) {
    	int highest = 0;
    	for(int i = 0; i < in.length; i++) {
    		if(in[i].getCost() > highest) {
    			highest = in[i].getCost();
    		}
    	}
    	return highest;
    }
    
    static int getLowestBid(Transaction [] in) {
    	int lowest = Integer.MAX_VALUE;
    	for(int i = 0; i < in.length; i++) {
    		if(in[i].getCost() < lowest) {
    			lowest = in[i].getCost();
    		}
    	}
    	return lowest;
    }
}
