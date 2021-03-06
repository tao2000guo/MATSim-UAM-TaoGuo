package net.bhl.matsim.uam.events;

import java.util.HashSet;
import java.util.Set;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Route;
import org.matsim.contrib.dvrp.passenger.PassengerEngine;
import org.matsim.contrib.dvrp.run.DvrpMode;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.framework.MobsimPassengerAgent;
import org.matsim.core.mobsim.framework.PlanAgent;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.agents.WithinDayAgentUtils;
import org.matsim.pt.PtConstants;

import com.google.inject.Inject;

import net.bhl.matsim.uam.qsim.UAMTripInfo;
import net.bhl.matsim.uam.run.UAMConstants;

public class UAMPrebookVehicle implements PersonDepartureEventHandler {
	Set<Id<Person>> bookedTrips = new HashSet<>();
	@Inject
	@DvrpMode(UAMConstants.uam)
	private PassengerEngine passengerEngine;

	@Inject
	private QSim qsim;

	@Inject
	@DvrpMode(UAMConstants.uam)
	Network network;

	private Set<String> modesRequiringManualUAMPrebooking;

	public void initiateUAMDepartureHandler() {
		if (modesRequiringManualUAMPrebooking == null) {
			modesRequiringManualUAMPrebooking = new HashSet<>();
			String mainMode = this.qsim.getScenario().getConfig().getModules().get("qsim").getParams().get("mainMode");
			if (mainMode.contains(UAMConstants.access + TransportMode.car))
				modesRequiringManualUAMPrebooking.add(UAMConstants.access + TransportMode.car);
			if (mainMode.contains(UAMConstants.egress + TransportMode.car))
				modesRequiringManualUAMPrebooking.add(UAMConstants.egress + TransportMode.car);
		}
		// else: already initiated
	}

	public void manualUAMPrebooking(String mode, double now, Id<Person> id, Id<Link> linkId) {
		if (modesRequiringManualUAMPrebooking.contains(mode))
			handleDeparture(now, qsim.getAgents().get(id), linkId);
	}

	private void performPrebooking(Leg uamLeg, MobsimAgent agent, double departureTime, double submissionTime) {
		Route uamRoute = uamLeg.getRoute();

		Link startLink = network.getLinks().get(uamRoute.getStartLinkId());
		Link endLink = network.getLinks().get(uamRoute.getEndLinkId());

		UAMTripInfo tripInfo = new UAMTripInfo(startLink, endLink, uamRoute, departureTime);
		passengerEngine.bookTrip((MobsimPassengerAgent) agent, tripInfo);
	}

	public boolean handleDeparture(double now, MobsimAgent agent, Id<Link> linkId) {

		if (agent instanceof PlanAgent) {
			if (agent.getMode().startsWith(UAMConstants.access)) {
				Plan plan = ((PlanAgent) agent).getCurrentPlan();
				final Integer planElementsIndex = WithinDayAgentUtils.getCurrentPlanElementIndex(agent);
				final Leg accessLeg = (Leg) plan.getPlanElements().get(planElementsIndex);
				final Leg leg = (Leg) plan.getPlanElements().get(planElementsIndex + 2);
				Activity uam_interaction = (Activity) plan.getPlanElements().get(planElementsIndex + 1);

				performPrebooking(leg, agent,
						now + uam_interaction.getMaximumDuration().seconds()
								+ (accessLeg.getTravelTime().seconds() <= 0 ? 1 : accessLeg.getTravelTime().seconds()),
						now);
			} else if (agent.getMode().equals(TransportMode.transit_walk)
					|| agent.getMode().equals(TransportMode.access_walk)) {
				Plan plan = ((PlanAgent) agent).getCurrentPlan();
				final Integer planElementsIndex = WithinDayAgentUtils.getCurrentPlanElementIndex(agent);
				if (isUamTrip(plan, planElementsIndex)) {
					if (!bookedTrips.contains(agent.getId())) {
						double travelTime = getTravelTime(plan, planElementsIndex);
						final Leg uamLeg = getUamLeg(plan, planElementsIndex);
						Activity uam_interaction = (Activity) plan.getPlanElements().get(planElementsIndex + 1);

						performPrebooking(uamLeg, agent, now + uam_interaction.getMaximumDuration().seconds()
								+ (travelTime <= 0 ? 1 : travelTime), now);
					}
				}

			} else if (agent.getMode().equals(UAMConstants.uam))
				bookedTrips.remove(agent.getId());
		}

		return false;
	}

	private Leg getUamLeg(Plan plan, Integer planElementsIndex) {
		while (true) {
			PlanElement pe = plan.getPlanElements().get(planElementsIndex);
			if (pe instanceof Leg) {
				if (((Leg) pe).getMode().equals(UAMConstants.uam))
					return (Leg) pe;
			}
			planElementsIndex++;
		}
	}

	private double getTravelTime(Plan plan, Integer planElementsIndex) {
		boolean found = false;
		int index = planElementsIndex;
		double travelTime = 0.0;
		while (!found) {
			PlanElement pe = plan.getPlanElements().get(index);
			if (pe instanceof Leg) {
				if (((Leg) pe).getMode().equals(UAMConstants.uam))
					found = true;
				else
					travelTime += ((Leg) pe).getTravelTime().seconds();
			}
			index++;
		}
		return travelTime;
	}

	private boolean isUamTrip(Plan plan, Integer planElementsIndex) {
		int index = planElementsIndex + 1;
		while (true) {
			PlanElement pe = plan.getPlanElements().get(index);
			if (pe instanceof Activity) {
				if (((Activity) pe).getType().equals(UAMConstants.interaction))
					return true;
				else if (((Activity) pe).getType().equals(PtConstants.TRANSIT_ACTIVITY_TYPE)) {
					index++;

					continue;
				} else
					return false;
			}
			index++;
		}

	}

	@Override
	public void reset(int iteration) {
		// After iteration "self-destruct" otherwise old event handlers will still exist
		// in new iterations next to
		// that iterations, newly created event handler
		// this.departureHandler = null;
		this.bookedTrips = new HashSet<>();
		initiateUAMDepartureHandler();
	}

	@Override
	public void handleEvent(PersonDepartureEvent event) {
		// Required only for network modes (i.e. access_uam_car) since the default
		// VehicluarDepartureHandler handles
		// such departures, resulting in UAMDepartureHandler not being able to register
		// such bookings
		manualUAMPrebooking(event.getLegMode(), event.getTime(), event.getPersonId(), event.getLinkId());
	}
}
