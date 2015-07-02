package otp;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.router.MainModeIdentifier;
import org.matsim.core.router.RoutingContext;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.TripRouterFactory;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.impl.InputStreamGraphSource;
import org.opentripplanner.routing.services.GraphService;

import java.io.File;
import java.util.List;

public final class OTPTripRouterFactory implements
		TripRouterFactory {
	// TripRouterFactory: Matsim interface for routers
	
	private CoordinateTransformation ct;
	private String day;
	private String timeZone;
    private TransitSchedule transitSchedule;
	private Network matsimNetwork;
	private boolean chooseRandomlyAnOtpParameterProfile;
	private int numOfAlternativeItinerariesToChooseFromRandomly;
	private final GraphService graphservice;

	public OTPTripRouterFactory(TransitSchedule transitSchedule, Network matsimNetwork, 
			CoordinateTransformation ct, String day, String timeZone, String graphFile,
			boolean chooseRandomlyAnOtpParameterProfile, 
			int numOfAlternativeItinerariesToChooseFromRandomly) {
		graphservice = createGraphService(graphFile);
		this.transitSchedule = transitSchedule;
		this.matsimNetwork = matsimNetwork;
		this.ct = ct;
		this.day = day;
		this.timeZone = timeZone;
		this.chooseRandomlyAnOtpParameterProfile = chooseRandomlyAnOtpParameterProfile;
		this.numOfAlternativeItinerariesToChooseFromRandomly = numOfAlternativeItinerariesToChooseFromRandomly;
	}

    public static GraphService createGraphService(String graphFile) {
		GraphService graphService = new GraphService();
		graphService.registerGraph("", InputStreamGraphSource.newFileGraphSource("", new File(graphFile), Graph.LoadLevel.FULL));
		return graphService;
    }


    @Override
	public TripRouter instantiateAndConfigureTripRouter(RoutingContext iterationContext) {
		TripRouter tripRouter = new TripRouter();
		// OtpRoutingModule uses the modes teleport_begin_or_end and teleport_transit_stop_area
		// -> new modes whose main mode is unknown
		// -> return main mode pt
		tripRouter.setMainModeIdentifier(new MainModeIdentifier(){

			@Override
			public String identifyMainMode(List<? extends PlanElement> tripElements) {
				String mode = ((Leg) tripElements.get( 0 )).getMode();
				if(mode.equals(TransportMode.transit_walk) || 
						mode.equals(OTPRoutingModule.TELEPORT_BEGIN_END) || 
						mode.equals(OTPRoutingModule.TELEPORT_TRANSIT_STOP_AREA)
								// add walk and bike because they should be routed using otp, too
								|| mode.equals(TransportMode.walk)
								|| mode.equals(TransportMode.bike)
								){
					return TransportMode.pt;
				} else {
					return mode;
				}
			}
			
		});
		tripRouter.setRoutingModule("pt", new OTPRoutingModule(graphservice, transitSchedule,
				matsimNetwork, day, timeZone, ct, chooseRandomlyAnOtpParameterProfile,
				numOfAlternativeItinerariesToChooseFromRandomly));
		return tripRouter;
	}
	
}