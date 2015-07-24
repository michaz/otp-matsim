package vbb;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup.ActivityParams;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.StrategyConfigGroup.StrategySettings;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.network.MatsimNetworkReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordImpl;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.pt.router.TransitRouter;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.VehicleReaderV1;
import otp.OTPTripRouterFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 
 * TODO: Vbb data: 3 vehicles in endless simulation:
 * continuous bus trips are represented in independent segments in vbb gtfs data, e.g. bus 245 from Zoo to Nordbahnhof is cut at Lesser-Ury-Weg, maybe because the headsign changes, otp webserver router knows that and delivers "stay onboard" message -> not only circular lines are splitted; in gtfs transfer.txt 120s min transfer but column stop sequence is continuous; does not work at Grumbkowstr. (Berlin) (VBB:9131002) where circular bus line 250 continues its trip, otp recommends to alight, wait 14 min and board the following bus
 * data has transfer times in transfer.txt
 * 
 * 
 * @author gleich
 *
 */
public class GeneratePopulationAndRunScenario {

    private Scenario scenario;
    private ArrayList<TransitStopFacility> facs;

    public static void main(String[] args) throws IOException, ClassNotFoundException {
		new GeneratePopulationAndRunScenario().run();
	}

	private void run() {
		Config config = ConfigUtils.createConfig();
		config.transit().setUseTransit(true);
		config.transit().setTransitScheduleFile(Consts.TRANSIT_SCHEDULE_FILE);
		config.transit().setVehiclesFile(Consts.TRANSIT_VEHICLE_FILE);
		config.network().setInputFile(Consts.NETWORK_FILE);
		config.controler().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controler().setMobsim("qsim");
		config.qsim().setSnapshotStyle(QSimConfigGroup.SnapshotStyle.queue);
		config.qsim().setSnapshotPeriod(1);
		config.qsim().setRemoveStuckVehicles(false);
		config.transitRouter().setMaxBeelineWalkConnectionDistance(1.0);
		config.controler().setOutputDirectory(Consts.BASEDIR + "testOneIteration");
		
		config.controler().setWriteEventsInterval(1);		
		config.controler().setLastIteration(0);
		config.controler().setWritePlansInterval(1);
		config.qsim().setEndTime(30*60*60);
		
		ActivityParams home = new ActivityParams("home");
		home.setTypicalDuration(12*60*60);
		config.planCalcScore().addActivityParams(home);
		ActivityParams work = new ActivityParams("work");
		work.setTypicalDuration(8*60*60);
		config.planCalcScore().addActivityParams(work);
		config.planCalcScore().setWriteExperiencedPlans(true);
		config.strategy().setMaxAgentPlanMemorySize(5);
		
		StrategySettings reRoute = new StrategySettings(Id.create("1", StrategySettings.class));
		reRoute.setStrategyName("ReRoute");
		reRoute.setWeight(0.2);
		reRoute.setDisableAfter(15);
		StrategySettings expBeta = new StrategySettings(Id.create("2", StrategySettings.class));
		expBeta.setStrategyName("ChangeExpBeta");
		expBeta.setWeight(0.6);
		
		config.strategy().addStrategySettings(expBeta);
		config.strategy().addStrategySettings(reRoute);

        scenario = ScenarioUtils.createScenario(config);

		new MatsimNetworkReader(scenario).readFile(config.network().getInputFile());
		new TransitScheduleReader(scenario).readFile(config.transit().getTransitScheduleFile());
		new VehicleReaderV1(scenario.getTransitVehicles()).readFile(config.transit().getVehiclesFile());
		
        facs = new ArrayList<>(scenario.getTransitSchedule().getFacilities().values());
        System.out.println("Scenario has " + scenario.getNetwork().getLinks().size() + " links.");

		final OTPTripRouterFactory trf = new OTPTripRouterFactory(scenario.getTransitSchedule(),
				scenario.getNetwork(), TransformationFactory.getCoordinateTransformation( 
						Consts.TARGET_SCENARIO_COORDINATE_SYSTEM, TransformationFactory.WGS84),
                Consts.DATE,
                Consts.TIME_ZONE,
                Consts.BASEDIR,
                true, 3, 
                Consts.USE_CREATE_PSEUDO_NETWORK_INSTEAD_OF_OTP_PT_NETWORK);
        
        generatePopulation();
        
        new PopulationWriter(scenario.getPopulation(), scenario.getNetwork()).writeV5("output/population_vor_Simulation.xml");

		Controler controler = new Controler(scenario);
		controler.addOverridingModule(new AbstractModule() {

			@Override
			public void install() {
				bind(TransitRouter.class).to(DummyTransitRouter.class);
			}
			
		});
		controler.setTripRouterFactory(trf);

		controler.run();

	}
	
	static class DummyTransitRouter implements TransitRouter {
		@Override
		public List<Leg> calcRoute(Coord fromCoord, Coord toCoord, double departureTime, Person person) {
			throw new RuntimeException();
		}
		
	}

	private void generatePopulation() {
		for (int i=0; i<10; ++i) {
			Coord source = randomCoord();
			Coord sink = randomCoord();
			Person person = scenario.getPopulation().getFactory().createPerson(Id.create(Integer.toString(i), Person.class));
			Plan plan = scenario.getPopulation().getFactory().createPlan();
			plan.addActivity(createHomeStart(source));
			List<Leg> homeWork = createLeg();
			for (Leg leg : homeWork) {
				plan.addLeg(leg);
			}
			plan.addActivity(createWork(sink));
			List<Leg> workHome = createLeg();
			for (Leg leg : workHome) {
				plan.addLeg(leg);
			}
			plan.addActivity(createHomeEnd(source));
			person.addPlan(plan);
			scenario.getPopulation().addPerson(person);
		}
	}

	private List<Leg> createLeg() {
		Leg leg = scenario.getPopulation().getFactory().createLeg(TransportMode.pt);
		return Arrays.asList(leg);
	}

    private Coord randomCoord() {
        int nFac = (int) (facs.size() * Math.random());
        Coord coordsOfATransitStop = facs.get(nFac).getCoord();
        coordsOfATransitStop.setXY(coordsOfATransitStop.getX() + Math.random() * 1000 - 500, coordsOfATransitStop.getY() + Math.random() * 1000 - 500);
        // People live within 1 km of transit stops. :-)
		return coordsOfATransitStop;
    }

	private Activity createWork(Coord workLocation) {
		Activity activity = scenario.getPopulation().getFactory().createActivityFromCoord("work", workLocation);
		activity.setEndTime(17*60*60);
		return activity;
	}

	private Activity createHomeStart(Coord homeLocation) {
		Activity activity = scenario.getPopulation().getFactory().createActivityFromCoord("home", homeLocation);
		activity.setEndTime(9*60*60);
		return activity;
	}
	
	private Activity createHomeEnd(Coord homeLocation) {
		Activity activity = scenario.getPopulation().getFactory().createActivityFromCoord("home", homeLocation);
		activity.setEndTime(Double.POSITIVE_INFINITY);
		return activity;
	}

}