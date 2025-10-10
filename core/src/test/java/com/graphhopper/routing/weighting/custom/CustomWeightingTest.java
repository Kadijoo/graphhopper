package com.graphhopper.routing.weighting.custom;

import com.bedatadriven.jackson.datatype.jts.JtsModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graphhopper.json.Statement;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.*;
import com.graphhopper.routing.querygraph.VirtualEdgeIteratorState;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.weighting.DefaultTurnCostProvider;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.Graph;
import com.graphhopper.util.*;
import com.github.javafaker.Faker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.graphhopper.json.Statement.*;
import static com.graphhopper.json.Statement.Op.LIMIT;
import static com.graphhopper.json.Statement.Op.MULTIPLY;
import static com.graphhopper.routing.ev.RoadClass.*;
import static com.graphhopper.routing.weighting.TurnCostProvider.NO_TURN_COST_PROVIDER;
import static com.graphhopper.util.GHUtility.getEdge;
import static org.junit.jupiter.api.Assertions.*;


import java.util.Locale;
import java.util.Random;

class CustomWeightingTest {
    BaseGraph graph;
    DecimalEncodedValue avSpeedEnc;
    BooleanEncodedValue accessEnc;
    DecimalEncodedValue maxSpeedEnc;
    EnumEncodedValue<RoadClass> roadClassEnc;
    EncodingManager encodingManager;
    BooleanEncodedValue turnRestrictionEnc = TurnRestriction.create("car");

    @BeforeEach
    public void setup() {
        accessEnc = VehicleAccess.create("car");
        avSpeedEnc = VehicleSpeed.create("car", 5, 5, true);
        encodingManager = new EncodingManager.Builder().add(accessEnc).add(avSpeedEnc)
                .add(Toll.create())
                .add(Hazmat.create())
                .add(RouteNetwork.create(BikeNetwork.KEY))
                .add(MaxSpeed.create())
                .add(RoadClass.create())
                .add(RoadClassLink.create())
                .addTurnCostEncodedValue(turnRestrictionEnc)
                .build();
        maxSpeedEnc = encodingManager.getDecimalEncodedValue(MaxSpeed.KEY);
        roadClassEnc = encodingManager.getEnumEncodedValue(KEY, RoadClass.class);
        graph = new BaseGraph.Builder(encodingManager).create();
    }

    private void setTurnRestriction(Graph graph, int from, int via, int to) {
        graph.getTurnCostStorage().set(turnRestrictionEnc, getEdge(graph, from, via).getEdge(), via, getEdge(graph, via, to).getEdge(), true);
    }

    private CustomModel createSpeedCustomModel(DecimalEncodedValue speedEnc) {
        CustomModel customModel = new CustomModel();
        customModel.addToSpeed(If("true", LIMIT, speedEnc.getName()));
        return customModel;
    }

    private Weighting createWeighting(CustomModel vehicleModel) {
        return CustomModelParser.createWeighting(encodingManager, NO_TURN_COST_PROVIDER, vehicleModel);
    }

    @Test
    public void speedOnly() {
        // 50km/h -> 72s per km, 100km/h -> 36s per km
        EdgeIteratorState edge = graph.edge(0, 1).setDistance(1000).set(avSpeedEnc, 50, 100);
        CustomModel customModel = createSpeedCustomModel(avSpeedEnc)
                .setDistanceInfluence(0d);
        Weighting weighting = createWeighting(customModel);

        assertEquals(72, weighting.calcEdgeWeight(edge, false), 1.e-6);
        assertEquals(36, weighting.calcEdgeWeight(edge, true), 1.e-6);
    }

    @Test
    public void withPriority() {
        // 25km/h -> 144s per km, 50km/h -> 72s per km, 100km/h -> 36s per km
        EdgeIteratorState slow = graph.edge(0, 1).set(avSpeedEnc, 25, 25).setDistance(1000).
                set(roadClassEnc, SECONDARY);
        EdgeIteratorState medium = graph.edge(0, 1).set(avSpeedEnc, 50, 50).setDistance(1000).
                set(roadClassEnc, SECONDARY);
        EdgeIteratorState fast = graph.edge(0, 1).set(avSpeedEnc, 100).setDistance(1000).
                set(roadClassEnc, SECONDARY);

        Weighting weighting = createWeighting(createSpeedCustomModel(avSpeedEnc));
        assertEquals(144, weighting.calcEdgeWeight(slow, false), .1);
        assertEquals(72, weighting.calcEdgeWeight(medium, false), .1);
        assertEquals(36, weighting.calcEdgeWeight(fast, false), .1);

        // if we reduce the priority we get higher edge weights
        weighting = CustomModelParser.createWeighting(encodingManager, NO_TURN_COST_PROVIDER,
                createSpeedCustomModel(avSpeedEnc)
                        .addToPriority(If("road_class == SECONDARY", MULTIPLY, "0.5"))
        );
        assertEquals(2 * 144, weighting.calcEdgeWeight(slow, false), .1);
        assertEquals(2 * 72, weighting.calcEdgeWeight(medium, false), .1);
        assertEquals(2 * 36, weighting.calcEdgeWeight(fast, false), .1);
    }

    @Test
    public void withDistanceInfluence() {
        EdgeIteratorState edge1 = graph.edge(0, 1).setDistance(10_000).set(avSpeedEnc, 50);
        EdgeIteratorState edge2 = graph.edge(0, 1).setDistance(5_000).set(avSpeedEnc, 25);
        Weighting weighting = createWeighting(createSpeedCustomModel(avSpeedEnc).setDistanceInfluence(0d));
        assertEquals(720, weighting.calcEdgeWeight(edge1, false), .1);
        assertEquals(720_000, weighting.calcEdgeMillis(edge1, false), .1);
        // we can also imagine a shorter but slower road that takes the same time
        assertEquals(720, weighting.calcEdgeWeight(edge2, false), .1);
        assertEquals(720_000, weighting.calcEdgeMillis(edge2, false), .1);

        // distance_influence=30 means that for every kilometer we get additional costs of 30s, so +300s here
        weighting = createWeighting(createSpeedCustomModel(avSpeedEnc).setDistanceInfluence(30d));
        assertEquals(1020, weighting.calcEdgeWeight(edge1, false), .1);
        // for the shorter but slower edge the distance influence also increases the weight, but not as much because it is shorter
        assertEquals(870, weighting.calcEdgeWeight(edge2, false), .1);
        // ... the travelling times stay the same
        assertEquals(720_000, weighting.calcEdgeMillis(edge1, false), .1);
        assertEquals(720_000, weighting.calcEdgeMillis(edge2, false), .1);
    }

    @Test
    public void testSpeedFactorBooleanEV() {
        EdgeIteratorState edge = graph.edge(0, 1).set(avSpeedEnc, 15, 15).setDistance(10);
        Weighting weighting = createWeighting(createSpeedCustomModel(avSpeedEnc).setDistanceInfluence(70d));
        assertEquals(3.1, weighting.calcEdgeWeight(edge, false), 0.01);
        // here we increase weight for edges that are road class links
        weighting = createWeighting(createSpeedCustomModel(avSpeedEnc)
                .setDistanceInfluence(70d)
                .addToPriority(If(RoadClassLink.KEY, MULTIPLY, "0.5")));
        BooleanEncodedValue rcLinkEnc = encodingManager.getBooleanEncodedValue(RoadClassLink.KEY);
        assertEquals(3.1, weighting.calcEdgeWeight(edge.set(rcLinkEnc, false), false), 0.01);
        assertEquals(5.5, weighting.calcEdgeWeight(edge.set(rcLinkEnc, true), false), 0.01);
    }

    @Test
    public void testBoolean() {
        BooleanEncodedValue specialEnc = new SimpleBooleanEncodedValue("special", true);
        DecimalEncodedValue avSpeedEnc = VehicleSpeed.create("car", 5, 5, false);
        encodingManager = new EncodingManager.Builder().add(specialEnc).add(avSpeedEnc).build();
        graph = new BaseGraph.Builder(encodingManager).create();

        EdgeIteratorState edge = graph.edge(0, 1).set(specialEnc, false, true).set(avSpeedEnc, 15).setDistance(10);

        Weighting weighting = createWeighting(createSpeedCustomModel(avSpeedEnc).setDistanceInfluence(70d));
        assertEquals(3.1, weighting.calcEdgeWeight(edge, false), 0.01);
        weighting = createWeighting(createSpeedCustomModel(avSpeedEnc)
                .setDistanceInfluence(70d)
                .addToPriority(If("special == true", MULTIPLY, "0.8"))
                .addToPriority(If("special == false", MULTIPLY, "0.4")));
        assertEquals(6.7, weighting.calcEdgeWeight(edge, false), 0.01);
        assertEquals(3.7, weighting.calcEdgeWeight(edge, true), 0.01);
    }

    @Test
    public void testSpeedFactorAndPriority() {
        EdgeIteratorState primary = graph.edge(0, 1).setDistance(10).
                set(roadClassEnc, PRIMARY).set(avSpeedEnc, 80);
        EdgeIteratorState secondary = graph.edge(1, 2).setDistance(10).
                set(roadClassEnc, SECONDARY).set(avSpeedEnc, 70);

        CustomModel customModel = createSpeedCustomModel(avSpeedEnc).setDistanceInfluence(70d).
                addToPriority(If("road_class != PRIMARY", MULTIPLY, "0.5")).
                addToSpeed(If("road_class != PRIMARY", MULTIPLY, "0.9"));
        Weighting weighting = createWeighting(customModel);
        assertEquals(1.15, weighting.calcEdgeWeight(primary, false), 0.01);
        assertEquals(1.84, weighting.calcEdgeWeight(secondary, false), 0.01);

        customModel = createSpeedCustomModel(avSpeedEnc).setDistanceInfluence(70d).
                addToPriority(If("road_class == PRIMARY", MULTIPLY, "1.0")).
                addToPriority(Else(MULTIPLY, "0.5")).
                addToSpeed(If("road_class != PRIMARY", MULTIPLY, "0.9"));
        weighting = createWeighting(customModel);
        assertEquals(1.15, weighting.calcEdgeWeight(primary, false), 0.01);
        assertEquals(1.84, weighting.calcEdgeWeight(secondary, false), 0.01);
    }

    @Test
    public void testIssueSameKey() {
        EdgeIteratorState withToll = graph.edge(0, 1).setDistance(10).set(avSpeedEnc, 80);
        EdgeIteratorState noToll = graph.edge(1, 2).setDistance(10).set(avSpeedEnc, 80);

        CustomModel customModel = createSpeedCustomModel(avSpeedEnc);
        customModel.setDistanceInfluence(70d).
                addToSpeed(If("toll == HGV || toll == ALL", MULTIPLY, "0.8")).
                addToSpeed(If("hazmat != NO", MULTIPLY, "0.8"));
        Weighting weighting = createWeighting(customModel);
        assertEquals(1.26, weighting.calcEdgeWeight(withToll, false), 0.01);
        assertEquals(1.26, weighting.calcEdgeWeight(noToll, false), 0.01);

        customModel = createSpeedCustomModel(avSpeedEnc);
        customModel.setDistanceInfluence(70d).
                addToSpeed(If("bike_network != OTHER", MULTIPLY, "0.8"));
        weighting = createWeighting(customModel);
        assertEquals(1.26, weighting.calcEdgeWeight(withToll, false), 0.01);
        assertEquals(1.26, weighting.calcEdgeWeight(noToll, false), 0.01);
    }

    @Test
    public void testFirstMatch() {
        EdgeIteratorState primary = graph.edge(0, 1).setDistance(10).
                set(roadClassEnc, PRIMARY).set(avSpeedEnc, 80);
        EdgeIteratorState secondary = graph.edge(1, 2).setDistance(10).
                set(roadClassEnc, SECONDARY).set(avSpeedEnc, 70);

        CustomModel customModel = createSpeedCustomModel(avSpeedEnc).setDistanceInfluence(70d).
                addToSpeed(If("road_class == PRIMARY", MULTIPLY, "0.8"));
        Weighting weighting = createWeighting(customModel);
        assertEquals(1.26, weighting.calcEdgeWeight(primary, false), 0.01);
        assertEquals(1.21, weighting.calcEdgeWeight(secondary, false), 0.01);

        customModel.addToPriority(If("road_class == PRIMARY", MULTIPLY, "0.9"));
        customModel.addToPriority(ElseIf("road_class == SECONDARY", MULTIPLY, "0.8"));

        weighting = createWeighting(customModel);
        assertEquals(1.33, weighting.calcEdgeWeight(primary, false), 0.01);
        assertEquals(1.34, weighting.calcEdgeWeight(secondary, false), 0.01);
    }

    @Test
    public void testSpeedBiggerThan() {
        EdgeIteratorState edge40 = graph.edge(0, 1).setDistance(10).set(avSpeedEnc, 40);
        EdgeIteratorState edge50 = graph.edge(1, 2).setDistance(10).set(avSpeedEnc, 50);

        CustomModel customModel = createSpeedCustomModel(avSpeedEnc).setDistanceInfluence(70d).
                addToPriority(If("car_average_speed > 40", MULTIPLY, "0.5"));
        Weighting weighting = createWeighting(customModel);

        assertEquals(1.60, weighting.calcEdgeWeight(edge40, false), 0.01);
        assertEquals(2.14, weighting.calcEdgeWeight(edge50, false), 0.01);
    }

    @Test
    public void testRoadClass() {
        EdgeIteratorState primary = graph.edge(0, 1).setDistance(10).
                set(roadClassEnc, PRIMARY).set(avSpeedEnc, 80);
        EdgeIteratorState secondary = graph.edge(1, 2).setDistance(10).
                set(roadClassEnc, SECONDARY).set(avSpeedEnc, 80);
        CustomModel customModel = createSpeedCustomModel(avSpeedEnc).setDistanceInfluence(70d).
                addToPriority(If("road_class == PRIMARY", MULTIPLY, "0.5"));
        Weighting weighting = createWeighting(customModel);
        assertEquals(1.6, weighting.calcEdgeWeight(primary, false), 0.01);
        assertEquals(1.15, weighting.calcEdgeWeight(secondary, false), 0.01);
    }

    @Test
    public void testArea() throws Exception {
        EdgeIteratorState edge1 = graph.edge(0, 1).setDistance(10).
                set(roadClassEnc, PRIMARY).set(avSpeedEnc, 80);
        EdgeIteratorState edge2 = graph.edge(2, 3).setDistance(10).
                set(roadClassEnc, PRIMARY).set(avSpeedEnc, 80);
        graph.getNodeAccess().setNode(0, 50.0120, 11.582);
        graph.getNodeAccess().setNode(1, 50.0125, 11.585);
        graph.getNodeAccess().setNode(2, 40.0, 8.0);
        graph.getNodeAccess().setNode(3, 40.1, 8.1);
        CustomModel customModel = createSpeedCustomModel(avSpeedEnc).setDistanceInfluence(70d).
                addToPriority(If("in_custom1", MULTIPLY, "0.5"));

        ObjectMapper om = new ObjectMapper().registerModule(new JtsModule());
        JsonFeature json = om.readValue("{ \"geometry\":{ \"type\": \"Polygon\", \"coordinates\": " +
                "[[[11.5818,50.0126], [11.5818,50.0119], [11.5861,50.0119], [11.5861,50.0126], [11.5818,50.0126]]] }}", JsonFeature.class);
        json.setId("custom1");
        customModel.getAreas().getFeatures().add(json);

        Weighting weighting = createWeighting(customModel);
        // edge1 is located within the area custom1, edge2 is not
        assertEquals(1.6, weighting.calcEdgeWeight(edge1, false), 0.01);
        assertEquals(1.15, weighting.calcEdgeWeight(edge2, false), 0.01);
    }

    @Test
    public void testMaxSpeed() {
        assertEquals(155, avSpeedEnc.getMaxOrMaxStorableDecimal(), 0.1);

        assertEquals(1d / 72 * 3.6, createWeighting(createSpeedCustomModel(avSpeedEnc).
                addToSpeed(If("true", LIMIT, "72"))).calcMinWeightPerDistance(), .001);

        // ignore too big limit to let custom model compatibility not break when max speed of encoded value later decreases
        assertEquals(1d / 155 * 3.6, createWeighting(createSpeedCustomModel(avSpeedEnc).
                addToSpeed(If("true", LIMIT, "180"))).calcMinWeightPerDistance(), .001);

        // reduce speed only a bit
        assertEquals(1d / 150 * 3.6, createWeighting(createSpeedCustomModel(avSpeedEnc).
                addToSpeed(If("road_class == SERVICE", MULTIPLY, "1.5")).
                addToSpeed(If("true", LIMIT, "150"))).calcMinWeightPerDistance(), .001);
    }

    @Test
    public void testMaxPriority() {
        double maxSpeed = 155;
        assertEquals(maxSpeed, avSpeedEnc.getMaxOrMaxStorableDecimal(), 0.1);
        assertEquals(1d / maxSpeed / 0.5 * 3.6, createWeighting(createSpeedCustomModel(avSpeedEnc).
                addToPriority(If("true", MULTIPLY, "0.5"))).calcMinWeightPerDistance(), 1.e-6);

        // ignore too big limit
        assertEquals(1d / maxSpeed / 1.0 * 3.6, createWeighting(createSpeedCustomModel(avSpeedEnc).
                addToPriority(If("true", LIMIT, "2.0"))).calcMinWeightPerDistance(), 1.e-6);

        // priority bigger 1 is fine (if CustomModel not in query)
        assertEquals(1d / maxSpeed / 2.0 * 3.6, createWeighting(createSpeedCustomModel(avSpeedEnc).
                addToPriority(If("true", MULTIPLY, "3.0")).
                addToPriority(If("true", LIMIT, "2.0"))).calcMinWeightPerDistance(), 1.e-6);
        assertEquals(1d / maxSpeed / 1.5 * 3.6, createWeighting(createSpeedCustomModel(avSpeedEnc).
                addToPriority(If("true", MULTIPLY, "1.5"))).calcMinWeightPerDistance(), 1.e-6);

        // pick maximum priority from value even if this is for a special case
        assertEquals(1d / maxSpeed / 3.0 * 3.6, createWeighting(createSpeedCustomModel(avSpeedEnc).
                addToPriority(If("road_class == SERVICE", MULTIPLY, "3.0"))).calcMinWeightPerDistance(), 1.e-6);

        // do NOT pick maximum priority when it is for a special case
        assertEquals(1d / maxSpeed / 1.0 * 3.6, createWeighting(createSpeedCustomModel(avSpeedEnc).
                addToPriority(If("road_class == SERVICE", MULTIPLY, "0.5"))).calcMinWeightPerDistance(), 1.e-6);
    }

    @Test
    public void maxSpeedViolated_bug_2307() {
        EdgeIteratorState motorway = graph.edge(0, 1).setDistance(10).
                set(roadClassEnc, MOTORWAY).set(avSpeedEnc, 80);
        CustomModel customModel = createSpeedCustomModel(avSpeedEnc)
                .setDistanceInfluence(70d)
                .addToSpeed(If("road_class == MOTORWAY", Statement.Op.MULTIPLY, "0.7"))
                .addToSpeed(Else(LIMIT, "30"));
        Weighting weighting = createWeighting(customModel);
        assertEquals(1.3429, weighting.calcEdgeWeight(motorway, false), 1e-4);
        assertEquals(10 / (80 * 0.7 / 3.6) * 1000, weighting.calcEdgeMillis(motorway, false), 1);
    }

    @Test
    public void bugWithNaNForBarrierEdges() {
        EdgeIteratorState motorway = graph.edge(0, 1).setDistance(0).
                set(roadClassEnc, MOTORWAY).set(avSpeedEnc, 80);
        CustomModel customModel = createSpeedCustomModel(avSpeedEnc)
                .addToPriority(If("road_class == MOTORWAY", Statement.Op.MULTIPLY, "0"));
        Weighting weighting = createWeighting(customModel);
        assertFalse(Double.isNaN(weighting.calcEdgeWeight(motorway, false)));
        assertTrue(Double.isInfinite(weighting.calcEdgeWeight(motorway, false)));
    }

    @Test
    public void testMinWeightHasSameUnitAs_getWeight() {
        EdgeIteratorState edge = graph.edge(0, 1).set(avSpeedEnc, 140, 0).setDistance(10);
        CustomModel customModel = createSpeedCustomModel(avSpeedEnc);
        Weighting weighting = createWeighting(customModel);
        assertEquals(weighting.calcMinWeightPerDistance() * 10, weighting.calcEdgeWeight(edge, false), 1e-8);
    }

    @Test
    public void testWeightWrongHeading() {
        CustomModel customModel = createSpeedCustomModel(avSpeedEnc).setHeadingPenalty(100);
        Weighting weighting = createWeighting(customModel);
        EdgeIteratorState edge = graph.edge(1, 2)
                .set(avSpeedEnc, 10, 10)
                .setDistance(10).setWayGeometry(Helper.createPointList(51, 0, 51, 1));
        VirtualEdgeIteratorState virtEdge = new VirtualEdgeIteratorState(edge.getEdgeKey(), 99, 5, 6, edge.getDistance(), edge.getFlags(),
                edge.getKeyValues(), edge.fetchWayGeometry(FetchMode.PILLAR_ONLY), false);
        double time = weighting.calcEdgeWeight(virtEdge, false);

        virtEdge.setUnfavored(true);
        // heading penalty on edge
        assertEquals(time + 100, weighting.calcEdgeWeight(virtEdge, false), 1e-8);
        // only after setting it
        virtEdge.setUnfavored(true);
        assertEquals(time + 100, weighting.calcEdgeWeight(virtEdge, true), 1e-8);
        // but not after releasing it
        virtEdge.setUnfavored(false);
        assertEquals(time, weighting.calcEdgeWeight(virtEdge, true), 1e-8);

        // test default penalty
        virtEdge.setUnfavored(true);
        customModel = createSpeedCustomModel(avSpeedEnc);
        weighting = createWeighting(customModel);
        assertEquals(time + Parameters.Routing.DEFAULT_HEADING_PENALTY, weighting.calcEdgeWeight(virtEdge, false), 1e-8);
    }

    @Test
    public void testSpeed0() {
        EdgeIteratorState edge = graph.edge(0, 1).setDistance(10);
        CustomModel customModel = createSpeedCustomModel(avSpeedEnc);
        Weighting weighting = createWeighting(customModel);
        edge.set(avSpeedEnc, 0);
        assertEquals(1.0 / 0, weighting.calcEdgeWeight(edge, false), 1e-8);

        // 0 / 0 returns NaN but calcWeight should not return NaN!
        edge.setDistance(0);
        assertEquals(1.0 / 0, weighting.calcEdgeWeight(edge, false), 1e-8);
    }

    @Test
    public void testTime() {
        DecimalEncodedValue speedEnc = new DecimalEncodedValueImpl("speed", 4, 2, true);
        EncodingManager em = EncodingManager.start().add(speedEnc).build();
        BaseGraph g = new BaseGraph.Builder(em).create();
        EdgeIteratorState edge = g.edge(0, 1).set(speedEnc, 15, 10).setDistance(100_000);
        CustomModel customModel = createSpeedCustomModel(speedEnc);
        Weighting w = CustomModelParser.createWeighting(em, NO_TURN_COST_PROVIDER, customModel);
        assertEquals(375 * 60 * 1000, w.calcEdgeMillis(edge, false));
        assertEquals(600 * 60 * 1000, w.calcEdgeMillis(edge, true));
    }

    @Test
    public void calcWeightAndTime_withTurnCosts() {
        BaseGraph graph = new BaseGraph.Builder(encodingManager).withTurnCosts(true).create();
        CustomModel customModel = createSpeedCustomModel(avSpeedEnc);
        Weighting weighting = CustomModelParser.createWeighting(encodingManager,
                new DefaultTurnCostProvider(turnRestrictionEnc, null, graph, new TurnCostsConfig()), customModel);
        graph.edge(0, 1).set(avSpeedEnc, 60, 60).setDistance(100);
        EdgeIteratorState edge = graph.edge(1, 2).set(avSpeedEnc, 60, 60).setDistance(100);
        setTurnRestriction(graph, 0, 1, 2);
        assertTrue(Double.isInfinite(GHUtility.calcWeightWithTurnWeight(weighting, edge, false, 0)));
        // the time only reflects the time for the edge, the turn time is 0
        assertEquals(6000, GHUtility.calcMillisWithTurnMillis(weighting, edge, false, 0));
    }

    @Test
    public void calcWeightAndTime_uTurnCosts() {
        BaseGraph graph = new BaseGraph.Builder(encodingManager).withTurnCosts(true).create();
        CustomModel customModel = createSpeedCustomModel(avSpeedEnc);
        Weighting weighting = CustomModelParser.createWeighting(encodingManager,
                new DefaultTurnCostProvider(turnRestrictionEnc, null, graph, new TurnCostsConfig().setUTurnCosts(40)), customModel);
        EdgeIteratorState edge = graph.edge(0, 1).set(avSpeedEnc, 60, 60).setDistance(100);
        assertEquals(6 + 40, GHUtility.calcWeightWithTurnWeight(weighting, edge, false, 0), 1.e-6);
        assertEquals(6 * 1000, GHUtility.calcMillisWithTurnMillis(weighting, edge, false, 0), 1.e-6);
    }

    @Test
    public void testDestinationTag() {
        DecimalEncodedValue carSpeedEnc = new DecimalEncodedValueImpl("car_speed", 5, 5, false);
        DecimalEncodedValue bikeSpeedEnc = new DecimalEncodedValueImpl("bike_speed", 4, 2, false);
        EncodingManager em = EncodingManager.start().add(carSpeedEnc).add(bikeSpeedEnc).add(RoadAccess.create()).build();
        BaseGraph graph = new BaseGraph.Builder(em).create();
        EdgeIteratorState edge = graph.edge(0, 1).setDistance(1000);
        edge.set(carSpeedEnc, 60);
        edge.set(bikeSpeedEnc, 18);
        EnumEncodedValue<RoadAccess> roadAccessEnc = em.getEnumEncodedValue(RoadAccess.KEY, RoadAccess.class);

        CustomModel customModel = createSpeedCustomModel(carSpeedEnc)
                .addToPriority(If("road_access == DESTINATION", MULTIPLY, ".1"));
        Weighting weighting = CustomModelParser.createWeighting(em, NO_TURN_COST_PROVIDER, customModel);

        CustomModel bikeCustomModel = createSpeedCustomModel(bikeSpeedEnc);
        Weighting bikeWeighting = CustomModelParser.createWeighting(em, NO_TURN_COST_PROVIDER, bikeCustomModel);

        edge.set(roadAccessEnc, RoadAccess.YES);
        assertEquals(60, weighting.calcEdgeWeight(edge, false), 1.e-6);
        assertEquals(200, bikeWeighting.calcEdgeWeight(edge, false), 1.e-6);

        // the destination tag does not change the weight for the bike weighting
        edge.set(roadAccessEnc, RoadAccess.DESTINATION);
        assertEquals(600, weighting.calcEdgeWeight(edge, false), 0.1);
        assertEquals(200, bikeWeighting.calcEdgeWeight(edge, false), 0.1);
    }

    @Test
    public void testPrivateTag() {
        DecimalEncodedValue carSpeedEnc = new DecimalEncodedValueImpl("car_speed", 5, 5, false);
        DecimalEncodedValue bikeSpeedEnc = new DecimalEncodedValueImpl("bike_speed", 4, 2, false);
        EncodingManager em = EncodingManager.start().add(carSpeedEnc).add(bikeSpeedEnc).add(RoadAccess.create()).build();
        BaseGraph graph = new BaseGraph.Builder(em).create();
        EdgeIteratorState edge = graph.edge(0, 1).setDistance(1000);
        edge.set(carSpeedEnc, 60);
        edge.set(bikeSpeedEnc, 18);
        EnumEncodedValue<RoadAccess> roadAccessEnc = em.getEnumEncodedValue(RoadAccess.KEY, RoadAccess.class);

        CustomModel customModel = createSpeedCustomModel(carSpeedEnc)
                .addToPriority(If("road_access == PRIVATE", MULTIPLY, ".1"));
        Weighting weighting = CustomModelParser.createWeighting(em, NO_TURN_COST_PROVIDER, customModel);

        customModel = createSpeedCustomModel(bikeSpeedEnc)
                .addToPriority(If("road_access == PRIVATE", MULTIPLY, "0.8333"));
        Weighting bikeWeighting = CustomModelParser.createWeighting(em, NO_TURN_COST_PROVIDER, customModel);

        ReaderWay way = new ReaderWay(1);
        way.setTag("highway", "secondary");

        edge.set(roadAccessEnc, RoadAccess.YES);
        assertEquals(60, weighting.calcEdgeWeight(edge, false), .01);
        assertEquals(200, bikeWeighting.calcEdgeWeight(edge, false), .01);

        edge.set(roadAccessEnc, RoadAccess.PRIVATE);
        assertEquals(600, weighting.calcEdgeWeight(edge, false), .01);
        // private should influence bike only slightly
        assertEquals(240, bikeWeighting.calcEdgeWeight(edge, false), .01);
    }

// Les 7 nouveaux tests

/**
 * Nom : T1 - Symétrie: même poids en aller/retour pour un edge bidirectionnel
 * Intention : Vérifier que la pondération est symétrique quand les vitesses avant/arrière sont identiques.
 * Données : Arête 0→1 de 200 m, vitesses 30 km/h dans les deux sens, distance_influence=0.
 * Oracle : calcEdgeWeight(e,false) == calcEdgeWeight(e,true) car, à vitesses égales et sans pénalité directionnelle,
 *          la formule de poids est indépendante du sens.
*/  
@Test
public void weightIsSymmetricForBidirectionalEdge() {
    EdgeIteratorState e = graph.edge(0, 1).set(avSpeedEnc, 30, 30).setDistance(200);
    Weighting w = createWeighting(createSpeedCustomModel(avSpeedEnc).setDistanceInfluence(0d));
    double fwd = w.calcEdgeWeight(e, /*reverse=*/false);
    double rev = w.calcEdgeWeight(e, /*reverse=*/true);
    // Pour un edge bidirectionnel avec mêmes vitesses, le poids doit être identique
    assertEquals(fwd, rev, 1e-6);
}

/**
 * Nom : T2 - Monotonie en distance: plus la distance est grande, plus le poids est grand
 * Intention : S'assurer qu'à vitesse constante, l'augmentation de distance entraîne un poids plus élevé.
 * Données : Deux arêtes 50 m et 500 m à 30 km/h, distance_influence=10 (pour rendre visible l'effet distance).
 * Oracle : w(longE) > w(shortE) car le terme de coût lié à la distance (et/ou au temps) croît avec la longueur.
 */
@Test
public void weightIncreasesWithDistance() {
    EdgeIteratorState shortE = graph.edge(2, 3).set(avSpeedEnc, 30, 30).setDistance(50);
    EdgeIteratorState longE  = graph.edge(3, 4).set(avSpeedEnc, 30, 30).setDistance(500);

    Weighting w = createWeighting(createSpeedCustomModel(avSpeedEnc).setDistanceInfluence(10d));

    double wShort = w.calcEdgeWeight(shortE, false);
    double wLong  = w.calcEdgeWeight(longE,  false);

    assertTrue(wLong > wShort, "Le poids doit croître avec la distance");
}

/**
 * Nom : T3 - DistanceInfluence: un DI plus élevé augmente le poids (toutes choses égales)
 * Intention : Valider que le paramètre distance_influence s'additionne au coût minimal par mètre.
 * Données : Même arête (250 m, 30 km/h) évaluée avec DI=0 puis DI=50.
 * Oracle : w(DI=50) > w(DI=0) car calcMinWeightPerDistance doit être majoré de DI/1000 (0.05 s/m ici).
 */
@Test
public void higherDistanceInfluenceIncreasesWeight() {
    EdgeIteratorState e = graph.edge(5, 6).set(avSpeedEnc, 30, 30).setDistance(250);

    Weighting w0  = createWeighting(createSpeedCustomModel(avSpeedEnc).setDistanceInfluence(0d));
    Weighting w50 = createWeighting(createSpeedCustomModel(avSpeedEnc).setDistanceInfluence(50d));

    double wNoDI  = w0.calcEdgeWeight(e, false);
    double wWithDI= w50.calcEdgeWeight(e, false);

    assertTrue(wWithDI > wNoDI, "Un DI plus grand doit produire un poids plus grand");
}

/**
 * Nom : T4 - Priority (BooleanEncoded) : MULTIPLY < 1 rend le poids plus grand
 * Intention : Vérifier que diminuer la priorité (×0.5) augmente le poids.
 * Données : Arête 120 m à 20 km/h ; règle "road_class_link ⇒ priority × 0.5" ; comparaison flag=false/true ; DI=20.
 * Oracle : w(flag=true) > w(flag=false), et w(flag=false) ≈ w(base sans règle), car la priorité réduite augmente
 *          le dénominateur effectif dans la conversion poids↔vitesse, donc le poids.
 */
@Test
public void priorityMultiplyReducesPriorityIncreasesWeight() {
    // edge sans flag 'link'
    EdgeIteratorState e = graph.edge(7, 8).set(avSpeedEnc, 20, 20).setDistance(120);
    BooleanEncodedValue rcLinkEnc = encodingManager.getBooleanEncodedValue(RoadClassLink.KEY);

    // Modèle sans règle de priority
    Weighting base = createWeighting(createSpeedCustomModel(avSpeedEnc).setDistanceInfluence(20d));

    // Modèle qui multiplie la priorité par 0.5 quand road_class_link = true
    Weighting withPrioMul = createWeighting(createSpeedCustomModel(avSpeedEnc)
            .setDistanceInfluence(20d)
            .addToPriority(If(RoadClassLink.KEY, MULTIPLY, "0.5")));

    double wNoFlag = withPrioMul.calcEdgeWeight(e.set(rcLinkEnc, false), false);
    double wFlag   = withPrioMul.calcEdgeWeight(e.set(rcLinkEnc, true),  false);

    // Avec priority * 0.5, la priorité baisse => le poids doit AUGMENTER
    assertTrue(wFlag > wNoFlag);

    // Vérification par rapport au modèle sans règle : le cas 'false' ne doit pas être pénalisé
    double wBase = base.calcEdgeWeight(e.set(rcLinkEnc, false), false);
    assertEquals(wBase, wNoFlag, 1e-6);
}

/**
 * Nom : T5 - car_access=false pénalise via CustomModel (priority MULTIPLY)
 * Intention : Confirmer que la règle "car_access == false ⇒ priority × 0.2" alourdit le coût.
 * Données : Arête 300 m à 25 km/h ; booléen car_access togglé ; DI=10.
 * Oracle : w(car_access=false) > w(car_access=true), car la priorité plus faible (×0.2) augmente le poids.
 */
@Test
public void carAccessFalsePenalizesViaCustomModel() {
    // Encodage car_access présent dans EncodingManager
    BooleanEncodedValue carAccessEnc = encodingManager.getBooleanEncodedValue("car_access");

    // Edge standard
    EdgeIteratorState e = graph.edge(9, 10).set(avSpeedEnc, 25, 25).setDistance(300);

    // CustomModel : si car_access == false, on réduit la priority (x0.2) => poids ↑
    CustomModel cm = createSpeedCustomModel(avSpeedEnc)
            .setDistanceInfluence(10d)
            .addToPriority(If("car_access == false", MULTIPLY, "0.2"));

    Weighting w = createWeighting(cm);

    double wYes = w.calcEdgeWeight(e.set(carAccessEnc, true, true), false);
    double wNo  = w.calcEdgeWeight(e.set(carAccessEnc, false, false), false);

    assertTrue(wNo > wYes, "La pénalité CustomModel sur car_access=false doit augmenter le poids");
}

/**
 * Nom : T6 - Distance nulle: poids nul (zéro distance doit donner zéro coût)
 * Intention : Garantir qu'une arête de distance 0 reporte un coût total nul, même avec distance_influence>0.
 * Données : Arête distance=0, 50 km/h, distance_influence=123.
 * Oracle : calcEdgeWeight == 0.0 car ni le terme temps (distance/vitesse) ni le terme distance_influence*n_km ne s'appliquent.
 */
@Test
public void zeroDistanceGivesZeroWeight() {
    EdgeIteratorState e = graph.edge(11, 12).set(avSpeedEnc, 50, 50).setDistance(0);
    Weighting w = createWeighting(createSpeedCustomModel(avSpeedEnc).setDistanceInfluence(123d));
    double weight = w.calcEdgeWeight(e, false);
    // Avec distance 0, ni terme temps ni terme distance ne s'appliquent -> le poids doit être ~0
    assertEquals(0.0, weight, 1e-9);
}

/**
 * Nom : T7 - (java-faker) Fuzz déterministe: poids croît avec la distance sur
 *       échantillon aléatoire
 * Intention : Tester la monotonie (d2>d1 ⇒ w2>w1) et la non-négativité des
 *             poids sur 20 cas aléatoires reproductibles.
 * Données : Faker(fr-CA) seed=42 ; d1 ∈ [1,200], d2=d1+inc avec inc ∈ [1,300] ;
 *           v ∈ [5,50] ; DI=10.
 * Oracle : Pour chaque paire : w2 > w1 (distance plus longue) et w1,w2 ≥ 0.
 *          La seed fixe assure la reproductibilité.
 */ 
 
 /** Justification du choix de Faker :
 * - Variabilité contrôlée : génère des paires (d1,d2) variées pour mieux
 *                           exercer la monotonie qu’avec 2–3 valeurs fixes.
 * - Pertinent pour la mutation : plus de diversité de cas augmente les chances de faire
 *                                tomber des mutants arithmétiques/conditionnels.
 * - Robustesse : on force d2>d1 et v≥5 pour éviter les cas dégénérés (zéro,
 *                négatifs).
 */
@Test
public void fakerBasedMonotonicitySample() {
    Faker faker = new Faker(new Locale("fr-CA"), new Random(42));

    // DistanceInfluence non nulle pour bien distinguer les poids par la distance
    Weighting w = createWeighting(createSpeedCustomModel(avSpeedEnc).setDistanceInfluence(10d));

    for (int i = 0; i < 20; i++) {
        //On utilise faker.random() pour éviter les surprises de numberBetween(...) selon les versions
        int d1i = Math.max(1, faker.random().nextInt(1, 200));   // [1,200]
        int inc = Math.max(1, faker.random().nextInt(1, 300));   // [1,300]
        double d1 = d1i;
        double d2 = d1 + inc;                                    // garantit d2 > d1

        int v = Math.max(5, faker.random().nextInt(5, 50));      // [5,50] km/h

        EdgeIteratorState e1 = graph.edge(100 + i*2,     100 + i*2 + 1).set(avSpeedEnc, v, v).setDistance(d1);
        EdgeIteratorState e2 = graph.edge(100 + i*2 + 1, 100 + i*2 + 2).set(avSpeedEnc, v, v).setDistance(d2);

        double w1 = w.calcEdgeWeight(e1, false);
        double w2 = w.calcEdgeWeight(e2, false);

        assertTrue(w1 >= 0 && w2 >= 0, "Le poids ne doit jamais être négatif");
        assertTrue(w2 > w1, "Monotonie violée pour distances " + d1 + " < " + d2 + " (v=" + v + ")");
    }


}

// Le score de mutation avec les tests originaux: 58%
// Le score de mutation avec les 7 nouveaux tests: 58%

//Comme les nouveaux tests ne détectent pas de nouveaux mutants, on ajoute 3 autres tests qui détectent
//de nouveaux mutants  

/**
 * Nom : T8 - calcMinWeightPerDistance augmente exactement de DI/1000
 * Intention : Vérifier que le terme distance_influence est bien ajouté (+) et
 *             non soustrait dans le calcul.
 * Données : Deux weightings identiques sauf DI=0 vs DI=50 ; on compare les
 *           valeurs de calcMinWeightPerDistance().
 * Oracle : with == base + 0.05 (50/1000) à 1e-9 près ; toute erreur de signe
 *          ferait échouer le test.
 * Justification : Le mutant MATH (remplacer + par -) survivait car aucun test ne validait précisément 
 *                 l’ajout de DI/1000. En comparant w50 à w0 + 0.05, toute soustraction erronée provoque
 *                 une différence → mutant tué.
 * Mutation ciblée : Replaced double addition with subtraction → KILLED
 * Raison : le test détecte toute inversion du signe (+ → -) dans calcMinWeightPerDistance.
 */
@Test
void minWeightPerDistanceIncreasesWithDI() {
    // DI est stocké en s/m : setDistanceInfluence(50) => +0.05 s/m
    Weighting w0  = createWeighting(createSpeedCustomModel(avSpeedEnc).setDistanceInfluence(0d));
    Weighting w50 = createWeighting(createSpeedCustomModel(avSpeedEnc).setDistanceInfluence(50d));

    double base = w0.calcMinWeightPerDistance();
    double with = w50.calcMinWeightPerDistance();

    // calcMinWeightPerDistance = 1/(vmax/3.6)/maxPrio  +  distanceInfluence/1000
    assertEquals(base + 0.05, with, 1e-9,
        "Le terme distanceInfluence doit s'ADDITIONNER (+), pas être soustrait");
}

/**
 * Nom : T9 - distance_influence négative rejetée (IllegalArgumentException)
 * Intention : Vérifier que la création d’un weighting avec DI < 0 lance une exception.
 * Données : CustomModel avec setDistanceInfluence(-1d).
 * Oracle : assertThrows(IllegalArgumentException) attendu.
 * Justification : Le mutant REMOVE_CONDITIONAL (remplacer comparaison par false) supprimait la vérification
 *                 de DI < 0. Sans ce test, le code acceptait un DI négatif sans erreur.
 *                 Ce test force l’exception → mutant tué.
 * Mutation ciblée : removed conditional - replaced comparison check with false → KILLED
 * Raison : le test échoue si l’exception n’est pas levée, détectant la suppression du garde-fou.
 */
@Test
void negativeDistanceInfluenceRejected() {
    assertThrows(IllegalArgumentException.class, () -> {
        // Dans CustomWeighting, toute DI < 0 doit lancer une IllegalArgumentException
        createWeighting(createSpeedCustomModel(avSpeedEnc).setDistanceInfluence(-1d));
    });
}

/**
 * Nom : T10 - hasTurnCosts() reflète la configuration (couvre NO_COVERAGE)
 * Intention : Couvrir les deux branches de hasTurnCosts() (avec et sans TurnCostProvider).
 * Données : (1) Graph sans turn-costs ⇒ false ; (2) Graph avec TurnCostProvider ⇒ true.
 * Oracle : assertFalse pour le premier, assertTrue pour le second.
 * Justification : Les mutants modifiaient la condition interne ou le retour (forcer true/false).
 *                 Ce test exerce les deux cas réels, donc toute altération du booléen est détectée.
 * Mutations ciblées :
 * - removed conditional - replaced equality check with true → KILLED
 * - replaced boolean return with false → KILLED
 * - replaced boolean return with true → KILLED
 * - removed conditional - replaced equality check with false → KILLED
 * Raison : le test échoue dès qu’un des retours est faussé (couverture complète des deux branches).
 */
@Test
void hasTurnCostsCoverage() {
    Weighting wNo = createWeighting(createSpeedCustomModel(avSpeedEnc));
    assertFalse(wNo.hasTurnCosts());

    BaseGraph g = new BaseGraph.Builder(encodingManager).withTurnCosts(true).create();
    DefaultTurnCostProvider tcp = new DefaultTurnCostProvider(turnRestrictionEnc, null, g,
            new TurnCostsConfig().setUTurnCosts(10));
    Weighting wYes = CustomModelParser.createWeighting(encodingManager, tcp, createSpeedCustomModel(avSpeedEnc));
    assertTrue(wYes.hasTurnCosts());
}

//Grâce aux 3 nouveaux tests, on détecte 6 nouveaux mutants.
//Le nouveau score de mutation devient 59%.

}
