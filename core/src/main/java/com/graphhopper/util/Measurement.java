/*
 *  Licensed to GraphHopper and Peter Karich under one or more contributor license
 *  agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the
 *  License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.util;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.routing.ch.PrepareContractionHierarchies;
import com.graphhopper.routing.util.*;
import com.graphhopper.storage.Graph;
import com.graphhopper.storage.GraphStorage;
import com.graphhopper.storage.index.Location2IDIndex;
import com.graphhopper.util.shapes.BBox;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Peter Karich
 */
public class Measurement
{
    public static void main( String[] strs )
    {
        new Measurement().start(CmdArgs.read(strs));
    }
    private static final Logger logger = LoggerFactory.getLogger(Measurement.class);
    private final Map<String, String> properties = new TreeMap<String, String>();
    private long seed;
    private int maxNode;

    // creates properties file in the format key=value
    // Every value is one y-value in a separate diagram with an identical x-value for every Measurement.start call
    void start( CmdArgs args )
    {
        long importTook = args.getLong("graph.importTime", -1);
        put("graph.importTime", importTook);

        String graphLocation = args.get("graph.location", "");
        if (Helper.isEmpty(graphLocation))
            throw new IllegalStateException("no graph.location specified");

        String propLocation = args.get("measurement.location", "");
        if (Helper.isEmpty(propLocation))
            propLocation = "measurement" + new SimpleDateFormat("yyyy-MM-dd_HH_mm_ss").format(new Date()) + ".properties";

        seed = args.getLong("measurement.seed", 123);
        String gitCommit = args.get("measurement.gitinfo", "");        
        int count = args.getInt("measurement.count", 5000);

        GraphHopper hopper = new GraphHopper()
        {
            @Override
            protected void prepare()
            {
                // do nothing
            }
        }.forDesktop().setEnableInstructions(false);
        if (!hopper.load(graphLocation))
            throw new IllegalStateException("Cannot load existing levelgraph at " + graphLocation);

        GraphStorage g = (GraphStorage) hopper.getGraph();
        if ("true".equals(g.getProperties().get("prepare.done")))
            throw new IllegalStateException("Graph has to be unprepared but wasn't!");

        String vehicleStr = "car";
        final FlagEncoder vehicle = hopper.getEncodingManager().getEncoder(vehicleStr);
        final WeightCalculation type = new FastestCalc(vehicle);
        StopWatch sw = new StopWatch().start();
        try
        {
            maxNode = g.getNodes();
            printGraphDetails(g);
            printLocation2IDQuery(g, hopper.getLocationIndex(), count);

            // Route via dijkstrabi. Normal routing takes a lot of time => smaller query number than CH
            // => values are not really comparable to routingCH as e.g. the mean distance etc is different            
            hopper.disableCHShortcuts();
            printTimeOfRouteQuery(hopper, count / 20, "routing", vehicleStr);

            System.gc();

            // route via CH. do preparation before
            PrepareContractionHierarchies prepare = new PrepareContractionHierarchies(vehicle, type).setGraph(g);
            printPreparationDetails(g, prepare);
            hopper.setCHShortcuts("fastest");
            printTimeOfRouteQuery(hopper, count, "routingCH", vehicleStr);
            logger.info("store into " + propLocation);
        } catch (Exception ex)
        {
            logger.error("Problem while measuring " + graphLocation, ex);
            put("error", ex.toString());
        } finally
        {
            put("measurement.gitinfo", gitCommit);
            put("measurement.count", count);
            put("measurement.seed", seed);
            put("measurement.time", sw.stop().getTime());
            System.gc();
            put("measurement.totalMB", Helper.getTotalMB());
            put("measurement.usedMB", Helper.getUsedMB());
            try
            {
                store(new FileWriter(propLocation), "measurement finish, "
                        + new Date().toString() + ", " + Constants.BUILD_DATE);
            } catch (IOException ex)
            {
                logger.error("Problem while storing properties " + graphLocation + ", " + propLocation, ex);
            }
        }
    }

    private void printGraphDetails( GraphStorage g )
    {
        // graph size (edge, node and storage size)
        put("graph.nodes", g.getNodes());
        put("graph.edges", g.getAllEdges().getMaxId());
        put("graph.sizeInMB", g.getCapacity() / Helper.MB);
        put("graph.encoder", g.getEncodingManager().getSingle().toString());
    }

    private void printPreparationDetails( Graph g, PrepareContractionHierarchies prepare )
    {
        // time(preparation) + shortcuts number
        StopWatch sw = new StopWatch().start();
        prepare.doWork();
        put("prepare.time", sw.stop().getTime());
        put("prepare.shortcuts", prepare.getShortcuts());
    }

    private void printLocation2IDQuery( Graph g, final Location2IDIndex idx, int count )
    {
        count *= 2;
        final BBox bbox = g.getBounds();
        final double latDelta = bbox.maxLat - bbox.minLat;
        final double lonDelta = bbox.maxLon - bbox.minLon;
        final Random rand = new Random(seed);
        MiniPerfTest miniPerf = new MiniPerfTest()
        {
            @Override
            public int doCalc( boolean warmup, int run )
            {
                double lat = rand.nextDouble() * latDelta + bbox.minLat;
                double lon = rand.nextDouble() * lonDelta + bbox.minLon;
                int val = idx.findID(lat, lon);
//                if (!warmup && val >= 0)
//                    list.add(val);

                return val;
            }
        }.setIterations(count).start();

        print("location2id", miniPerf);
    }

    private void printTimeOfRouteQuery( final GraphHopper hopper, int count, String prefix, final String vehicle )
    {
        final Graph g = hopper.getGraph();
        final AtomicLong maxDistance = new AtomicLong(0);
        final AtomicLong minDistance = new AtomicLong(Long.MAX_VALUE);
        final AtomicLong sum = new AtomicLong(0);
        final Random rand = new Random(seed);
        MiniPerfTest miniPerf = new MiniPerfTest()
        {
            @Override
            public int doCalc( boolean warmup, int run )
            {
                int from = rand.nextInt(maxNode);
                int to = rand.nextInt(maxNode);
                double fromLat = g.getLatitude(from);
                double fromLon = g.getLongitude(from);
                double toLat = g.getLatitude(to);
                double toLon = g.getLongitude(to);
                GHResponse res = hopper.route(new GHRequest(fromLat, fromLon, toLat, toLon).setType("fastest").setVehicle(vehicle));
                if (res.hasErrors())
                    throw new IllegalStateException("errors should NOT happen in Measurement! " + res.getErrors());

                if (!warmup)
                {
                    long dist = (long) res.getDistance();
                    sum.addAndGet(dist);
                    if (dist > maxDistance.get())
                        maxDistance.set(dist);

                    if (dist < minDistance.get())
                        minDistance.set(dist);
                }

                return res.getPoints().getSize();
            }
        }.setIterations(count).start();

        put(prefix + ".distanceMin", minDistance.get());
        put(prefix + ".distanceMean", (float) sum.get() / count);
        put(prefix + ".distanceMax", maxDistance.get());
        print(prefix, miniPerf);
    }

    void print( String prefix, MiniPerfTest perf )
    {
        logger.info(perf.getReport());
        put(prefix + ".sum", perf.getSum());
//        put(prefix+".rms", perf.getRMS());
        put(prefix + ".min", perf.getMin());
        put(prefix + ".mean", perf.getMean());
        put(prefix + ".max", perf.getMax());
    }

    void put( String key, Object val )
    {
        // convert object to string to make serialization possible
        properties.put(key, "" + val);
    }

    private void store( FileWriter fileWriter, String comment ) throws IOException
    {
        fileWriter.append("#" + comment + "\n");
        for (Entry<String, String> e : properties.entrySet())
        {
            fileWriter.append(e.getKey());
            fileWriter.append("=");
            fileWriter.append(e.getValue());
            fileWriter.append("\n");
        }
        fileWriter.flush();
    }
}
