/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.plugin.geospatial;

import com.esri.core.geometry.Point;
import com.esri.core.geometry.ogc.OGCGeometry;
import com.esri.core.geometry.ogc.OGCPoint;
import com.facebook.presto.common.block.Block;
import com.facebook.presto.common.block.BlockBuilder;
import com.facebook.presto.common.type.ArrayType;
import com.facebook.presto.geospatial.KdbTreeUtils;
import com.facebook.presto.geospatial.Rectangle;
import com.facebook.presto.geospatial.serde.EsriGeometrySerde;
import com.facebook.presto.operator.scalar.AbstractTestFunctions;
import com.google.common.collect.ImmutableList;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.facebook.presto.common.type.BigintType.BIGINT;
import static com.facebook.presto.common.type.BooleanType.BOOLEAN;
import static com.facebook.presto.common.type.DoubleType.DOUBLE;
import static com.facebook.presto.common.type.IntegerType.INTEGER;
import static com.facebook.presto.common.type.TinyintType.TINYINT;
import static com.facebook.presto.common.type.VarcharType.VARCHAR;
import static com.facebook.presto.geospatial.KdbTree.buildKdbTree;
import static com.facebook.presto.plugin.geospatial.GeoFunctions.stCentroid;
import static com.facebook.presto.plugin.geospatial.GeometryType.GEOMETRY;
import static com.facebook.presto.spi.StandardErrorCode.INVALID_FUNCTION_ARGUMENT;
import static java.lang.String.format;
import static org.testng.Assert.assertEquals;

public class TestGeoFunctions
        extends AbstractTestFunctions
{
    @BeforeClass
    protected void registerFunctions()
    {
        GeoPlugin plugin = new GeoPlugin();
        registerTypes(plugin);
        registerFunctions(plugin);
    }

    @Test
    public void testSpatialPartitions()
    {
        String kdbTreeJson = makeKdbTreeJson();

        assertSpatialPartitions(kdbTreeJson, "POINT EMPTY", null);
        // points inside partitions
        assertSpatialPartitions(kdbTreeJson, "POINT (0 0)", ImmutableList.of(0));
        assertSpatialPartitions(kdbTreeJson, "POINT (3 1)", ImmutableList.of(2));
        // point on the border between two partitions
        assertSpatialPartitions(kdbTreeJson, "POINT (1 2.5)", ImmutableList.of(1));
        // point at the corner of three partitions
        assertSpatialPartitions(kdbTreeJson, "POINT (4.5 2.5)", ImmutableList.of(4));
        // points outside
        assertSpatialPartitions(kdbTreeJson, "POINT (2 6)", ImmutableList.of());
        assertSpatialPartitions(kdbTreeJson, "POINT (3 -1)", ImmutableList.of());
        assertSpatialPartitions(kdbTreeJson, "POINT (10 3)", ImmutableList.of());

        // geometry within a partition
        assertSpatialPartitions(kdbTreeJson, "MULTIPOINT (5 0.1, 6 2)", ImmutableList.of(3));
        // geometries spanning multiple partitions
        assertSpatialPartitions(kdbTreeJson, "MULTIPOINT (5 0.1, 5.5 3, 6 2)", ImmutableList.of(4, 3));
        assertSpatialPartitions(kdbTreeJson, "MULTIPOINT (3 2, 8 3)", ImmutableList.of(5, 4, 3, 2));
        // geometry outside
        assertSpatialPartitions(kdbTreeJson, "MULTIPOINT (2 6, 3 7)", ImmutableList.of());

        // with distance
        assertSpatialPartitions(kdbTreeJson, "POINT EMPTY", 1.2, null);
        assertSpatialPartitions(kdbTreeJson, "POINT (1 1)", 1.2, ImmutableList.of(0));
        assertSpatialPartitions(kdbTreeJson, "POINT (1 1)", 2.3, ImmutableList.of(2, 1, 0));
        assertSpatialPartitions(kdbTreeJson, "MULTIPOINT (5 0.1, 6 2)", 0.2, ImmutableList.of(3));
        assertSpatialPartitions(kdbTreeJson, "MULTIPOINT (5 0.1, 6 2)", 1.2, ImmutableList.of(4, 3, 2));
        assertSpatialPartitions(kdbTreeJson, "MULTIPOINT (2 6, 3 7)", 1.2, ImmutableList.of());
    }

    private static String makeKdbTreeJson()
    {
        ImmutableList.Builder<Rectangle> rectangles = ImmutableList.builder();
        for (double x = 0; x < 10; x += 1) {
            for (double y = 0; y < 5; y += 1) {
                rectangles.add(new Rectangle(x, y, x + 1, y + 2));
            }
        }
        return KdbTreeUtils.toJson(buildKdbTree(10, new Rectangle(0, 0, 9, 4), rectangles.build()));
    }

    private void assertSpatialPartitions(String kdbTreeJson, String wkt, List<Integer> expectedPartitions)
    {
        assertFunction(format("spatial_partitions(cast('%s' as KdbTree), ST_GeometryFromText('%s'))", kdbTreeJson, wkt), new ArrayType(INTEGER), expectedPartitions);
    }

    private void assertSpatialPartitions(String kdbTreeJson, String wkt, double distance, List<Integer> expectedPartitions)
    {
        assertFunction(format("spatial_partitions(cast('%s' as KdbTree), ST_GeometryFromText('%s'), %s)", kdbTreeJson, wkt, distance), new ArrayType(INTEGER), expectedPartitions);
    }

    @Test
    public void testGeometryGetObjectValue()
    {
        BlockBuilder builder = GEOMETRY.createBlockBuilder(null, 1);
        GEOMETRY.writeSlice(builder, GeoFunctions.stPoint(1.2, 3.4));
        Block block = builder.build();

        assertEquals("POINT (1.2 3.4)", GEOMETRY.getObjectValue(null, block, 0));
    }

    @Test
    public void testSTPoint()
    {
        assertFunction("ST_AsText(ST_Point(1, 4))", VARCHAR, "POINT (1 4)");
        assertFunction("ST_AsText(ST_Point(122.3, 10.55))", VARCHAR, "POINT (122.3 10.55)");
    }

    @Test
    public void testSTLineFromText()
    {
        assertFunction("ST_AsText(ST_LineFromText('LINESTRING EMPTY'))", VARCHAR, "LINESTRING EMPTY");
        assertFunction("ST_AsText(ST_LineFromText('LINESTRING (1 1, 2 2, 1 3)'))", VARCHAR, "LINESTRING (1 1, 2 2, 1 3)");
        assertInvalidFunction("ST_AsText(ST_LineFromText('MULTILINESTRING EMPTY'))", "ST_LineFromText only applies to LINE_STRING. Input type is: MULTI_LINE_STRING");
        assertInvalidFunction("ST_AsText(ST_LineFromText('POLYGON ((1 1, 1 4, 4 4, 4 1, 1 1))'))", "ST_LineFromText only applies to LINE_STRING. Input type is: POLYGON");
        assertInvalidFunction("ST_LineFromText('LINESTRING (0 0)')", INVALID_FUNCTION_ARGUMENT, "Invalid WKT: Invalid number of points in LineString (found 1 - must be 0 or >= 2)");
        assertInvalidFunction("ST_LineFromText('LINESTRING (0 0, 1)')", INVALID_FUNCTION_ARGUMENT, "Invalid WKT: Expected number but found ')' (line 1)");
    }

    @Test
    public void testSTPolygon()
    {
        assertFunction("ST_AsText(ST_Polygon('POLYGON EMPTY'))", VARCHAR, "POLYGON EMPTY");
        assertFunction("ST_AsText(ST_Polygon('POLYGON ((1 1, 1 4, 4 4, 4 1, 1 1))'))", VARCHAR, "POLYGON ((1 1, 1 4, 4 4, 4 1, 1 1))");
        assertInvalidFunction("ST_AsText(ST_Polygon('LINESTRING (1 1, 2 2, 1 3)'))", "ST_Polygon only applies to POLYGON. Input type is: LINE_STRING");
        assertInvalidFunction("ST_Polygon('POLYGON((-1 1, 1 -1))')", INVALID_FUNCTION_ARGUMENT, "Invalid WKT: Points of LinearRing do not form a closed linestring");
    }

    @Test
    public void testSTArea()
    {
        assertArea("POLYGON ((2 2, 2 6, 6 6, 6 2, 2 2))", 16.0);
        assertArea("POLYGON EMPTY", 0.0);
        assertArea("LINESTRING (1 4, 2 5)", 0.0);
        assertArea("LINESTRING EMPTY", 0.0);
        assertArea("POINT (1 4)", 0.0);
        assertArea("POINT EMPTY", 0.0);
        assertArea("GEOMETRYCOLLECTION EMPTY", 0.0);

        // Test basic geometry collection. Area is the area of the polygon.
        assertArea("GEOMETRYCOLLECTION (POINT (8 8), LINESTRING (5 5, 6 6), POLYGON ((1 1, 3 1, 3 4, 1 4, 1 1)))", 6.0);

        // Test overlapping geometries. Area is the sum of the individual elements
        assertArea("GEOMETRYCOLLECTION (POLYGON ((0 0, 2 0, 2 2, 0 2, 0 0)), POLYGON ((1 1, 3 1, 3 3, 1 3, 1 1)))", 8.0);

        // Test nested geometry collection
        assertArea("GEOMETRYCOLLECTION (POLYGON ((0 0, 2 0, 2 2, 0 2, 0 0)), POLYGON ((1 1, 3 1, 3 3, 1 3, 1 1)), GEOMETRYCOLLECTION (POINT (8 8), LINESTRING (5 5, 6 6), POLYGON ((1 1, 3 1, 3 4, 1 4, 1 1))))", 14.0);
    }

    private void assertArea(String wkt, double expectedArea)
    {
        assertFunction(format("ST_Area(ST_GeometryFromText('%s'))", wkt), DOUBLE, expectedArea);
    }

    @Test
    public void testSTBuffer()
    {
        assertFunction("ST_AsText(ST_Buffer(ST_Point(0, 0), 0.5))", VARCHAR, "POLYGON ((0.5 0, 0.4903926402016152 -0.0975451610080641, 0.4619397662556434 -0.1913417161825449, 0.4157348061512726 -0.2777851165098011, 0.3535533905932738 -0.3535533905932737, 0.2777851165098011 -0.4157348061512726, 0.1913417161825449 -0.4619397662556434, 0.0975451610080642 -0.4903926402016152, 0 -0.5, -0.0975451610080641 -0.4903926402016152, -0.1913417161825449 -0.4619397662556434, -0.277785116509801 -0.4157348061512727, -0.3535533905932737 -0.3535533905932738, -0.4157348061512727 -0.2777851165098011, -0.4619397662556434 -0.1913417161825447, -0.4903926402016153 -0.0975451610080639, -0.5 0.0000000000000004, -0.4903926402016151 0.0975451610080646, -0.4619397662556431 0.1913417161825454, -0.4157348061512722 0.2777851165098017, -0.3535533905932732 0.3535533905932743, -0.2777851165098004 0.4157348061512731, -0.1913417161825439 0.4619397662556438, -0.097545161008063 0.4903926402016154, 0.0000000000000012 0.5, 0.0975451610080655 0.4903926402016149, 0.1913417161825462 0.4619397662556428, 0.2777851165098024 0.4157348061512717, 0.353553390593275 0.3535533905932726, 0.4157348061512736 0.2777851165097996, 0.4619397662556441 0.1913417161825431, 0.4903926402016156 0.0975451610080622, 0.5 0))");
        assertFunction("ST_AsText(ST_Buffer(ST_LineFromText('LINESTRING (0 0, 1 1, 2 0.5)'), 0.2))", VARCHAR, "POLYGON ((0.8585786437626906 1.1414213562373094, 0.8908600605480863 1.167596162296255, 0.9278541681368628 1.1865341227356967, 0.9679635513986066 1.1974174915274993, 1.0094562767938988 1.1997763219933664, 1.050540677712335 1.1935087592239118, 1.0894427190999916 1.1788854381999831, 2.0894427190999916 0.6788854381999831, 2.1226229200749436 0.6579987957938098, 2.1510907909991412 0.6310403482720258, 2.173752327557934 0.5990460936544217, 2.189736659610103 0.5632455532033676, 2.198429518239 0.5250145216112229, 2.1994968417625285 0.4858221959818642, 2.192897613536241 0.4471747154099183, 2.178885438199983 0.4105572809000084, 2.1579987957938096 0.3773770799250564, 2.131040348272026 0.3489092090008587, 2.099046093654422 0.3262476724420662, 2.0632455532033678 0.3102633403898972, 2.0250145216112228 0.3015704817609999, 1.985822195981864 0.3005031582374715, 1.9471747154099182 0.3071023864637593, 1.9105572809000084 0.3211145618000169, 1.0394906098164267 0.7566478973418077, 0.1414213562373095 -0.1414213562373095, 0.1111140466039205 -0.1662939224605091, 0.076536686473018 -0.1847759065022574, 0.0390180644032257 -0.1961570560806461, 0 -0.2, -0.0390180644032256 -0.1961570560806461, -0.076536686473018 -0.1847759065022574, -0.1111140466039204 -0.1662939224605091, -0.1414213562373095 -0.1414213562373095, -0.1662939224605091 -0.1111140466039204, -0.1847759065022574 -0.076536686473018, -0.1961570560806461 -0.0390180644032257, -0.2 -0, -0.1961570560806461 0.0390180644032257, -0.1847759065022573 0.076536686473018, -0.166293922460509 0.1111140466039205, -0.1414213562373095 0.1414213562373095, 0.8585786437626906 1.1414213562373094))");
        assertFunction("ST_AsText(ST_Buffer(ST_GeometryFromText('POLYGON ((0 0, 0 5, 5 5, 5 0, 0 0))'), 1.2))", VARCHAR, "POLYGON ((0 -1.2, -0.2341083864193544 -1.1769423364838763, -0.4592201188381084 -1.1086554390135437, -0.6666842796235226 -0.9977635347630542, -0.8485281374238572 -0.8485281374238569, -0.9977635347630545 -0.6666842796235223, -1.1086554390135441 -0.4592201188381076, -1.1769423364838765 -0.234108386419354, -1.2 0, -1.2 5, -1.1769423364838765 5.234108386419354, -1.1086554390135441 5.4592201188381075, -0.9977635347630543 5.666684279623523, -0.8485281374238569 5.848528137423857, -0.6666842796235223 5.997763534763054, -0.4592201188381076 6.108655439013544, -0.2341083864193538 6.176942336483877, 0 6.2, 5 6.2, 5.234108386419354 6.176942336483877, 5.4592201188381075 6.108655439013544, 5.666684279623523 5.997763534763054, 5.848528137423857 5.848528137423857, 5.997763534763054 5.666684279623523, 6.108655439013544 5.4592201188381075, 6.176942336483877 5.234108386419354, 6.2 5, 6.2 0, 6.176942336483877 -0.2341083864193539, 6.108655439013544 -0.4592201188381077, 5.997763534763054 -0.6666842796235226, 5.848528137423857 -0.8485281374238569, 5.666684279623523 -0.9977635347630542, 5.4592201188381075 -1.1086554390135441, 5.234108386419354 -1.1769423364838765, 5 -1.2, 0 -1.2))");

        // zero distance
        assertFunction("ST_AsText(ST_Buffer(ST_Point(0, 0), 0))", VARCHAR, "POINT (0 0)");
        assertFunction("ST_AsText(ST_Buffer(ST_LineFromText('LINESTRING (0 0, 1 1, 2 0.5)'), 0))", VARCHAR, "LINESTRING (0 0, 1 1, 2 0.5)");
        assertFunction("ST_AsText(ST_Buffer(ST_GeometryFromText('POLYGON ((0 0, 0 5, 5 5, 5 0, 0 0))'), 0))", VARCHAR, "POLYGON ((0 0, 0 5, 5 5, 5 0, 0 0))");

        // geometry collection
        assertFunction("ST_AsText(ST_Buffer(ST_Intersection(ST_GeometryFromText('MULTILINESTRING ((1 1, 5 1), (2 4, 4 4))'), ST_GeometryFromText('MULTILINESTRING ((3 4, 6 4), (5 0, 5 4))')), 0.2))", VARCHAR, "MULTIPOLYGON (((5.2 1, 5.196157056080646 0.9609819355967744, 5.184775906502257 0.9234633135269821, 5.166293922460509 0.8888859533960796, 5.141421356237309 0.8585786437626906, 5.11111404660392 0.8337060775394909, 5.076536686473018 0.8152240934977426, 5.039018064403225 0.803842943919354, 5 0.8, 4.960981935596775 0.803842943919354, 4.923463313526982 0.8152240934977426, 4.88888595339608 0.8337060775394909, 4.858578643762691 0.8585786437626904, 4.833706077539491 0.8888859533960796, 4.815224093497743 0.9234633135269821, 4.803842943919354 0.9609819355967745, 4.8 1.0000000000000002, 4.803842943919354 1.0390180644032259, 4.815224093497743 1.0765366864730181, 4.833706077539491 1.1111140466039207, 4.858578643762691 1.1414213562373097, 4.88888595339608 1.1662939224605093, 4.923463313526982 1.1847759065022574, 4.960981935596775 1.1961570560806463, 5.000000000000001 1.2, 5.039018064403226 1.196157056080646, 5.076536686473019 1.1847759065022572, 5.111114046603921 1.1662939224605087, 5.14142135623731 1.141421356237309, 5.166293922460509 1.1111140466039198, 5.184775906502257 1.0765366864730173, 5.196157056080646 1.039018064403225, 5.2 1)), ((4 4.2, 4.039018064403225 4.196157056080646, 4.076536686473018 4.184775906502257, 4.11111404660392 4.166293922460509, 4.141421356237309 4.141421356237309, 4.166293922460509 4.11111404660392, 4.184775906502257 4.076536686473018, 4.196157056080646 4.039018064403225, 4.2 4, 4.196157056080646 3.960981935596774, 4.184775906502257 3.923463313526982, 4.166293922460509 3.8888859533960796, 4.141421356237309 3.8585786437626903, 4.11111404660392 3.833706077539491, 4.076536686473018 3.8152240934977426, 4.039018064403225 3.8038429439193537, 4 3.8, 3 3.8, 2.960981935596774 3.8038429439193537, 2.923463313526982 3.8152240934977426, 2.8888859533960796 3.833706077539491, 2.8585786437626903 3.8585786437626903, 2.8337060775394907 3.8888859533960796, 2.8152240934977426 3.923463313526982, 2.8038429439193537 3.960981935596774, 2.8 4, 2.8038429439193537 4.039018064403225, 2.8152240934977426 4.076536686473018, 2.833706077539491 4.11111404660392, 2.8585786437626903 4.141421356237309, 2.8888859533960796 4.166293922460509, 2.923463313526982 4.184775906502257, 2.9609819355967746 4.196157056080646, 3 4.2, 4 4.2)))");

        // empty geometry
        assertFunction("ST_Buffer(ST_GeometryFromText('POINT EMPTY'), 1)", GEOMETRY, null);

        // negative distance
        assertInvalidFunction("ST_Buffer(ST_Point(0, 0), -1.2)", "distance is negative");
        assertInvalidFunction("ST_Buffer(ST_Point(0, 0), -infinity())", "distance is negative");

        // infinity() and nan() distance
        assertFunction("ST_AsText(ST_Buffer(ST_Point(0, 0), infinity()))", VARCHAR, "POLYGON EMPTY");
        assertInvalidFunction("ST_Buffer(ST_Point(0, 0), nan())", "distance is NaN");

        // For small polygons, there was a bug in ESRI that throw an NPE.  This
        // was fixed (https://github.com/Esri/geometry-api-java/pull/243) to
        // return an empty geometry instead. However, JTS does not suffer from
        // this problem.
        assertFunction("ST_AsText(ST_Buffer(ST_Buffer(ST_Point(177.50102959662, 64.726807421691), 0.0000000001), 0.00005))",
                VARCHAR, "POLYGON ((177.50107936028078 64.72681227844056, 177.50107936028078 64.72680256494145, 177.5010774479383 64.72679292130174, 177.5010736620884 64.72678379449388, 177.50106827679528 64.72677573803607, 177.50106131974323 64.72676877387859, 177.50105313736503 64.72676330997743, 177.50104413021958 64.72675958044903, 177.50103449281957 64.72675766189617, 177.5010247004399 64.72675766189425, 177.50101506303915 64.72675958044333, 177.50100605589225 64.72676330996819, 177.5009978735119 64.72676877386616, 177.5009909164571 64.7267757380209, 177.50098553116084 64.7267837944766, 177.50098174530737 64.72679292128298, 177.5009798329611 64.72680256492194, 177.5009798329592 64.72681227844056, 177.5009817453017 64.72682192208028, 177.50098553115157 64.72683104888813, 177.5009909164447 64.72683910534595, 177.50099787349674 64.72684606950342, 177.50100605587494 64.72685153340458, 177.5010150630204 64.72685526293299, 177.5010247004204 64.72685718148584, 177.50103449280007 64.72685718148776, 177.50104413020082 64.72685526293868, 177.50105313734772 64.72685153341382, 177.50106131972808 64.72684606951586, 177.50106827678286 64.72683910536111, 177.50107366207914 64.72683104890541, 177.5010774479326 64.72682192209903, 177.50107936027888 64.72681227846007, 177.50107936028078 64.72681227844056))");
        assertFunction("ST_AsText(ST_Buffer(ST_GeometryFromText('POLYGON ((177.0 64.0, 177.0000000001 64.0, 177.0000000001 64.0000000001, 177.0 64.0000000001, 177.0 64.0))'), 0.01))",
                VARCHAR, "POLYGON ((177 63.99, 176.99804909677985 63.99019214719597, 176.99617316567634 63.99076120467489, 176.99444429766982 63.99168530387698, 176.99292893218814 63.992928932188136, 176.99168530387698 63.9944442976698, 176.9907612046749 63.996173165676346, 176.99019214719596 63.99804909677984, 176.99 64, 176.99019214719596 64.00195090332016, 176.9907612046749 64.00382683442365, 176.99168530387698 64.0055557024302, 176.99292893218814 64.00707106791187, 176.99444429766982 64.00831469622302, 176.99617316567634 64.00923879542512, 176.99804909677985 64.00980785290403, 177 64.0100000001, 177.00195090332014 64.00980785290403, 177.00382683442365 64.00923879542512, 177.00555570243017 64.00831469622302, 177.00707106791185 64.00707106791187, 177.008314696223 64.0055557024302, 177.0092387954251 64.00382683442365, 177.00980785290403 64.00195090332016, 177.01000000009998 64.0000000001, 177.00980785290403 63.99804909677984, 177.0092387954251 63.996173165676346, 177.008314696223 63.9944442976698, 177.00707106791185 63.992928932188136, 177.00555570243017 63.99168530387698, 177.00382683442365 63.99076120467489, 177.00195090332014 63.99019214719597, 177.0000000001 63.99, 177 63.99))");
    }

    @Test
    public void testSTCentroid()
    {
        assertCentroid("LINESTRING EMPTY", new Point());
        assertCentroid("POINT (3 5)", new Point(3, 5));
        assertCentroid("MULTIPOINT (1 2, 2 4, 3 6, 4 8)", new Point(2.5, 5));
        assertCentroid("LINESTRING (1 1, 2 2, 3 3)", new Point(2, 2));
        assertCentroid("MULTILINESTRING ((1 1, 5 1), (2 4, 4 4))", new Point(3, 2));
        assertCentroid("POLYGON ((1 1, 1 4, 4 4, 4 1, 1 1))", new Point(2.5, 2.5));
        assertCentroid("POLYGON ((1 1, 5 1, 3 4, 1 1))", new Point(3, 2));
        assertCentroid("MULTIPOLYGON (((1 1, 1 3, 3 3, 3 1, 1 1)), ((2 4, 2 6, 6 6, 6 4, 2 4)))", new Point(3.3333333333333335, 4));
        assertCentroid("POLYGON ((0 0, 0 5, 5 5, 5 0, 0 0), (1 1, 1 2, 2 2, 2 1, 1 1))", new Point(2.5416666666666665, 2.5416666666666665));

        // invalid geometry
        assertApproximateCentroid("MULTIPOLYGON (((4.903234300000006 52.08474289999999, 4.903234265193165 52.084742934806826, 4.903234299999999 52.08474289999999, 4.903234300000006 52.08474289999999)))", new Point(4.9032343, 52.0847429), 1e-7);

        // Numerical stability tests
        assertApproximateCentroid(
                "MULTIPOLYGON (((153.492818 -28.13729, 153.492821 -28.137291, 153.492816 -28.137289, 153.492818 -28.13729)))",
                new Point(153.49282, -28.13729), 1e-5);
        assertApproximateCentroid(
                "MULTIPOLYGON (((153.112475 -28.360526, 153.1124759 -28.360527, 153.1124759 -28.360526, 153.112475 -28.360526)))",
                new Point(153.112475, -28.360526), 1e-5);
        assertApproximateCentroid(
                "POLYGON ((4.903234300000006 52.08474289999999, 4.903234265193165 52.084742934806826, 4.903234299999999 52.08474289999999, 4.903234300000006 52.08474289999999))",
                new Point(4.9032343, 52.0847429), 1e-6);
        assertApproximateCentroid(
                "MULTIPOLYGON (((4.903234300000006 52.08474289999999, 4.903234265193165 52.084742934806826, 4.903234299999999 52.08474289999999, 4.903234300000006 52.08474289999999)))",
                new Point(4.9032343, 52.0847429), 1e-6);
        assertApproximateCentroid(
                "POLYGON ((-81.0387349 29.20822, -81.039974 29.210597, -81.0410331 29.2101579, -81.0404758 29.2090879, -81.0404618 29.2090609, -81.040433 29.209005, -81.0404269 29.208993, -81.0404161 29.2089729, -81.0398001 29.20779, -81.0387349 29.20822), (-81.0404229 29.208986, -81.04042 29.2089809, -81.0404269 29.208993, -81.0404229 29.208986))",
                new Point(-81.039885, 29.209191), 1e-6);
    }

    private void assertCentroid(String wkt, Point centroid)
    {
        assertFunction(format("ST_AsText(ST_Centroid(ST_GeometryFromText('%s')))", wkt), VARCHAR, new OGCPoint(centroid, null).asText());
    }

    private void assertApproximateCentroid(String wkt, Point expectedCentroid, double epsilon)
    {
        OGCPoint actualCentroid = (OGCPoint) EsriGeometrySerde.deserialize(
                stCentroid(EsriGeometrySerde.serialize(OGCGeometry.fromText(wkt))));
        assertEquals(actualCentroid.X(), expectedCentroid.getX(), epsilon);
        assertEquals(actualCentroid.Y(), expectedCentroid.getY(), epsilon);
    }

    @Test
    public void testSTConvexHull()
    {
        // test empty geometry
        assertConvexHull("POINT EMPTY", "POINT EMPTY");
        assertConvexHull("MULTIPOINT EMPTY", "MULTIPOINT EMPTY");
        assertConvexHull("LINESTRING EMPTY", "LINESTRING EMPTY");
        assertConvexHull("MULTILINESTRING EMPTY", "MULTILINESTRING EMPTY");
        assertConvexHull("POLYGON EMPTY", "POLYGON EMPTY");
        assertConvexHull("MULTIPOLYGON EMPTY", "MULTIPOLYGON EMPTY");
        assertConvexHull("GEOMETRYCOLLECTION EMPTY", "GEOMETRYCOLLECTION EMPTY");
        assertConvexHull("GEOMETRYCOLLECTION (POINT (1 1), POINT EMPTY)", "POINT (1 1)");
        assertConvexHull("GEOMETRYCOLLECTION (GEOMETRYCOLLECTION (POINT (1 1), GEOMETRYCOLLECTION (POINT (1 5), POINT (4 5), GEOMETRYCOLLECTION (POINT (3 4), POINT EMPTY))))", "POLYGON ((1 1, 1 5, 4 5, 1 1))");

        // test single geometry
        assertConvexHull("POINT (1 1)", "POINT (1 1)");
        assertConvexHull("LINESTRING (1 1, 1 9, 2 2)", "POLYGON ((1 1, 1 9, 2 2, 1 1))");

        // convex single geometry
        assertConvexHull("LINESTRING (1 1, 1 9, 2 2, 1 1)", "POLYGON ((1 1, 1 9, 2 2, 1 1))");
        assertConvexHull("POLYGON ((0 0, 0 3, 2 4, 4 2, 3 0, 0 0))", "POLYGON ((0 0, 0 3, 2 4, 4 2, 3 0, 0 0))");

        // non-convex geometry
        assertConvexHull("LINESTRING (1 1, 1 9, 2 2, 1 1, 4 0)", "POLYGON ((1 1, 1 9, 4 0, 1 1))");
        assertConvexHull("POLYGON ((0 0, 0 3, 4 4, 1 1, 3 0, 0 0))", "POLYGON ((0 0, 0 3, 4 4, 3 0, 0 0))");

        // all points are on the same line
        assertConvexHull("LINESTRING (20 20, 30 30)", "LINESTRING (20 20, 30 30)");
        assertConvexHull("MULTILINESTRING ((0 0, 3 3), (1 1, 2 2), (2 2, 4 4), (5 5, 8 8))", "LINESTRING (0 0, 8 8)");
        assertConvexHull("MULTIPOINT (0 1, 1 2, 2 3, 3 4, 4 5, 5 6)", "LINESTRING (0 1, 5 6)");
        assertConvexHull("GEOMETRYCOLLECTION (POINT (0 0), LINESTRING (1 1, 4 4, 2 2), POINT (10 10), POLYGON ((5 5, 7 7, 6 6, 5 5)), POINT (2 2), LINESTRING (6 6, 9 9))", "LINESTRING (0 0, 10 10)");
        assertConvexHull("GEOMETRYCOLLECTION (GEOMETRYCOLLECTION (POINT (2 2), POINT (1 1)), POINT (3 3))", "LINESTRING (3 3, 1 1)");

        // not all points are on the same line
        assertConvexHull("MULTILINESTRING ((1 1, 5 1, 6 6), (2 4, 4 0), (2 -4, 4 4), (3 -2, 4 -3))", "POLYGON ((1 1, 2 4, 6 6, 5 1, 4 -3, 2 -4, 1 1))");
        assertConvexHull("MULTIPOINT (0 2, 1 0, 3 0, 4 0, 4 2, 2 2, 2 4)", "POLYGON ((0 2, 2 4, 4 2, 4 0, 1 0, 0 2))");
        assertConvexHull("MULTIPOLYGON (((0 3, 2 0, 3 6, 0 3), (2 1, 2 3, 5 3, 5 1, 2 1), (1 7, 2 4, 4 2, 5 6, 3 8, 1 7)))", "POLYGON ((0 3, 1 7, 3 8, 5 6, 5 1, 2 0, 0 3))");
        assertConvexHull("GEOMETRYCOLLECTION (POINT (2 3), LINESTRING (2 8, 7 10), POINT (8 10), POLYGON ((4 4, 4 8, 9 8, 6 6, 6 4, 8 3, 6 1, 4 4)), POINT (4 2), LINESTRING (3 6, 5 5), POLYGON ((7 5, 7 6, 8 6, 8 5, 7 5)))", "POLYGON ((2 3, 2 8, 7 10, 8 10, 9 8, 8 3, 6 1, 2 3))");
        assertConvexHull("GEOMETRYCOLLECTION (GEOMETRYCOLLECTION (POINT (2 3), LINESTRING (2 8, 7 10), GEOMETRYCOLLECTION (POINT (8 10))), POLYGON ((4 4, 4 8, 9 8, 6 6, 6 4, 8 3, 6 1, 4 4)), POINT (4 2), LINESTRING (3 6, 5 5), POLYGON ((7 5, 7 6, 8 6, 8 5, 7 5)))", "POLYGON ((2 3, 2 8, 7 10, 8 10, 9 8, 8 3, 6 1, 2 3))");

        // single-element multi-geometries and geometry collections
        assertConvexHull("MULTILINESTRING ((1 1, 5 1, 6 6))", "POLYGON ((1 1, 6 6, 5 1, 1 1))");
        assertConvexHull("MULTILINESTRING ((1 1, 5 1, 1 4, 5 4))", "POLYGON ((1 1, 1 4, 5 4, 5 1, 1 1))");
        assertConvexHull("MULTIPOINT (0 2)", "POINT (0 2)");
        assertConvexHull("MULTIPOLYGON (((0 3, 3 6, 2 0, 0 3)))", "POLYGON ((0 3, 3 6, 2 0, 0 3))");
        assertConvexHull("MULTIPOLYGON (((0 0, 4 0, 4 4, 0 4, 2 2, 0 0)))", "POLYGON ((0 0, 0 4, 4 4, 4 0, 0 0))");
        assertConvexHull("GEOMETRYCOLLECTION (POINT (2 3))", "POINT (2 3)");
        assertConvexHull("GEOMETRYCOLLECTION (LINESTRING (1 1, 5 1, 6 6))", "POLYGON ((1 1, 6 6, 5 1, 1 1))");
        assertConvexHull("GEOMETRYCOLLECTION (LINESTRING (1 1, 5 1, 1 4, 5 4))", "POLYGON ((1 1, 1 4, 5 4, 5 1, 1 1))");
        assertConvexHull("GEOMETRYCOLLECTION (POLYGON ((0 3, 3 6, 2 0, 0 3)))", "POLYGON ((0 3, 3 6, 2 0, 0 3))");
        assertConvexHull("GEOMETRYCOLLECTION (POLYGON ((0 0, 4 0, 4 4, 0 4, 2 2, 0 0)))", "POLYGON ((0 0, 0 4, 4 4, 4 0, 0 0))");
    }

    private void assertConvexHull(String inputWKT, String expectWKT)
    {
        assertFunction(format("ST_AsText(ST_ConvexHull(ST_GeometryFromText('%s')))", inputWKT), VARCHAR, expectWKT);
    }

    @Test
    public void testSTCoordDim()
    {
        assertFunction("ST_CoordDim(ST_GeometryFromText('POLYGON ((1 1, 1 4, 4 4, 4 1, 1 1))'))", TINYINT, (byte) 2);
        assertFunction("ST_CoordDim(ST_GeometryFromText('POLYGON EMPTY'))", TINYINT, (byte) 2);
        assertFunction("ST_CoordDim(ST_GeometryFromText('LINESTRING EMPTY'))", TINYINT, (byte) 2);
        assertFunction("ST_CoordDim(ST_GeometryFromText('POINT (1 4)'))", TINYINT, (byte) 2);
    }

    @Test
    public void testSTDimension()
    {
        assertFunction("ST_Dimension(ST_GeometryFromText('POLYGON EMPTY'))", TINYINT, (byte) 2);
        assertFunction("ST_Dimension(ST_GeometryFromText('POLYGON ((1 1, 1 4, 4 4, 4 1, 1 1))'))", TINYINT, (byte) 2);
        assertFunction("ST_Dimension(ST_GeometryFromText('LINESTRING EMPTY'))", TINYINT, (byte) 1);
        assertFunction("ST_Dimension(ST_GeometryFromText('POINT (1 4)'))", TINYINT, (byte) 0);
    }

    @Test
    public void testSTIsClosed()
    {
        assertFunction("ST_IsClosed(ST_GeometryFromText('LINESTRING (1 1, 2 2, 1 3, 1 1)'))", BOOLEAN, true);
        assertFunction("ST_IsClosed(ST_GeometryFromText('LINESTRING (1 1, 2 2, 1 3)'))", BOOLEAN, false);
        assertFunction("ST_IsClosed(ST_GeometryFromText('MULTILINESTRING ((1 1, 2 2, 1 3, 1 1), (4 4, 5 5))'))", BOOLEAN, false);
        assertFunction("ST_IsClosed(ST_GeometryFromText('MULTILINESTRING ((1 1, 2 2, 1 3, 1 1), (4 4, 5 4, 5 5, 4 5, 4 4))'))", BOOLEAN, true);
        assertInvalidFunction("ST_IsClosed(ST_GeometryFromText('POLYGON ((1 1, 1 4, 4 4, 4 1, 1 1))'))", "ST_IsClosed only applies to LINE_STRING or MULTI_LINE_STRING. Input type is: POLYGON");
    }

    @Test
    public void testSTIsEmpty()
    {
        assertFunction("ST_IsEmpty(ST_GeometryFromText('POINT (1.5 2.5)'))", BOOLEAN, false);
        assertFunction("ST_IsEmpty(ST_GeometryFromText('POLYGON EMPTY'))", BOOLEAN, true);
    }

    private void assertSimpleGeometry(String text)
    {
        assertFunction("ST_IsSimple(ST_GeometryFromText('" + text + "'))", BOOLEAN, true);
    }

    private void assertNotSimpleGeometry(String text)
    {
        assertFunction("ST_IsSimple(ST_GeometryFromText('" + text + "'))", BOOLEAN, false);
    }

    @Test
    public void testSTIsSimple()
    {
        assertSimpleGeometry("POINT (1.5 2.5)");
        assertSimpleGeometry("MULTIPOINT (1 2, 2 4, 3 6, 4 8)");
        assertNotSimpleGeometry("MULTIPOINT (1 2, 2 4, 3 6, 1 2)");
        assertSimpleGeometry("LINESTRING (8 4, 5 7)");
        assertSimpleGeometry("LINESTRING (1 1, 2 2, 1 3, 1 1)");
        assertNotSimpleGeometry("LINESTRING (0 0, 1 1, 1 0, 0 1)");
        assertSimpleGeometry("MULTILINESTRING ((1 1, 5 1), (2 4, 4 4))");
        assertNotSimpleGeometry("MULTILINESTRING ((1 1, 5 1), (2 4, 4 0))");
        assertSimpleGeometry("POLYGON EMPTY");
        assertSimpleGeometry("POLYGON ((2 0, 2 1, 3 1, 2 0))");
        assertSimpleGeometry("MULTIPOLYGON (((1 1, 1 3, 3 3, 3 1, 1 1)), ((2 4, 2 6, 6 6, 6 4, 2 4)))");
        assertNotSimpleGeometry("LINESTRING (0 0, -1 0.5, 0 1, 1 1, 1 0, 0 1, 0 0)");
        assertNotSimpleGeometry("MULTIPOINT ((0 0), (0 1), (1 1), (0 1))");
        assertNotSimpleGeometry("LINESTRING (0 0, -1 0.5, 0 1, 1 1, 1 0, 0 1, 0 0)");
    }

    @Test
    public void testSimplifyGeometry()
    {
        // Eliminate unnecessary points on the same line.
        assertFunction("ST_AsText(simplify_geometry(ST_GeometryFromText('POLYGON ((1 0, 2 1, 3 1, 3 1, 4 1, 1 0))'), 1.5))", VARCHAR, "POLYGON ((1 0, 2 1, 4 1, 1 0))");

        // Use distanceTolerance to control fidelity.
        assertFunction("ST_AsText(simplify_geometry(ST_GeometryFromText('POLYGON ((1 0, 1 1, 2 1, 2 3, 3 3, 3 1, 4 1, 4 0, 1 0))'), 1.0))", VARCHAR, "POLYGON ((1 0, 2 3, 3 3, 4 0, 1 0))");
        assertFunction("ST_AsText(simplify_geometry(ST_GeometryFromText('POLYGON ((1 0, 1 1, 2 1, 2 3, 3 3, 3 1, 4 1, 4 0, 1 0))'), 0.5))", VARCHAR, "POLYGON ((1 0, 1 1, 2 1, 2 3, 3 3, 3 1, 4 1, 4 0, 1 0))");

        // Negative distance tolerance is invalid.
        assertInvalidFunction("ST_AsText(simplify_geometry(ST_GeometryFromText('" + "POLYGON ((1 0, 1 1, 2 1, 2 3, 3 3, 3 1, 4 1, 4 0, 1 0))" + "'), -0.5))", "distanceTolerance is negative");
    }

    @Test
    public void testSTIsValid()
    {
        // empty geometries are valid
        assertValidGeometry("POINT EMPTY");
        assertValidGeometry("MULTIPOINT EMPTY");
        assertValidGeometry("LINESTRING EMPTY");
        assertValidGeometry("MULTILINESTRING EMPTY");
        assertValidGeometry("POLYGON EMPTY");
        assertValidGeometry("MULTIPOLYGON EMPTY");
        assertValidGeometry("GEOMETRYCOLLECTION EMPTY");

        // valid geometries
        assertValidGeometry("POINT (1 2)");
        assertValidGeometry("MULTIPOINT (1 2, 3 4)");
        assertValidGeometry("LINESTRING (0 0, 1 2, 3 4)");
        assertValidGeometry("MULTILINESTRING ((1 1, 5 1), (2 4, 4 4))");
        assertValidGeometry("POLYGON ((0 0, 0 1, 1 1, 1 0, 0 0))");
        assertValidGeometry("MULTIPOLYGON (((1 1, 1 3, 3 3, 3 1, 1 1)), ((2 4, 2 6, 6 6, 6 4, 2 4)))");
        assertValidGeometry("GEOMETRYCOLLECTION (POINT (1 2), LINESTRING (0 0, 1 2, 3 4), POLYGON ((0 0, 0 1, 1 1, 1 0, 0 0)))");
        assertValidGeometry("MULTIPOINT ((0 0), (0 1), (1 1), (0 1))");
        // JTS considers LineStrings with repeated points valid/simple (it drops the dups)
        assertValidGeometry("LINESTRING (0 0, 0 1, 0 1, 1 1, 1 0, 0 0)");
        // Valid but not simple
        assertValidGeometry("LINESTRING (0 0, -1 0.5, 0 1, 1 1, 1 0, 0 1, 0 0)");

        // invalid geometries
        assertInvalidGeometry("POLYGON ((0 0, 1 1, 0 1, 1 0, 0 0))");
        assertInvalidGeometry("POLYGON ((0 0, 0 1, 0 1, 1 1, 1 0, 0 0), (2 2, 2 3, 3 3, 3 2, 2 2))");
        assertInvalidGeometry("POLYGON ((0 0, 0 1, 1 1, 1 0, 0 0), (2 2, 2 3, 3 3, 3 2, 2 2))");
        assertInvalidGeometry("POLYGON ((0 0, 0 1, 2 1, 1 1, 1 0, 0 0))");
        assertInvalidGeometry("POLYGON ((0 0, 0 1, 1 1, 1 0, 0 0), (0 1, 1 1, 0.5 0.5, 0 1))");
        assertInvalidGeometry("POLYGON ((0 0, 0 1, 1 1, 1 0, 0 0), (0 0, 0.5 0.7, 1 1, 0.5 0.4, 0 0))");
        assertInvalidGeometry("POLYGON ((0 0, -1 0.5, 0 1, 1 1, 1 0, 0 1, 0 0))");
        assertInvalidGeometry("MULTIPOLYGON (((0 0, 0 1, 1 1, 1 0, 0 0)), ((0.5 0.5, 0.5 2, 2 2, 2 0.5, 0.5 0.5)))");
        assertInvalidGeometry("GEOMETRYCOLLECTION (POINT (1 2), POLYGON ((0 0, 0 1, 2 1, 1 1, 1 0, 0 0)))");

        // corner cases
        assertFunction("ST_IsValid(ST_GeometryFromText(null))", BOOLEAN, null);
        assertFunction("geometry_invalid_reason(ST_GeometryFromText(null))", VARCHAR, null);
    }

    private void assertValidGeometry(String wkt)
    {
        assertFunction("ST_IsValid(ST_GeometryFromText('" + wkt + "'))", BOOLEAN, true);
    }

    private void assertInvalidGeometry(String wkt)
    {
        assertFunction("ST_IsValid(ST_GeometryFromText('" + wkt + "'))", BOOLEAN, false);
    }

    @Test
    public void testGeometryInvalidReason()
    {
        // invalid geometries
        assertInvalidReason("MULTIPOINT ((0 0), (0 1), (1 1), (0 1))", "[MultiPoint] Repeated point: (0.0 1.0)");
        assertInvalidReason("LINESTRING (0 0, -1 0.5, 0 1, 1 1, 1 0, 0 1, 0 0)", "[LineString] Self-intersection at or near: (0.0 1.0)");
        assertInvalidReason("POLYGON ((0 0, 1 1, 0 1, 1 0, 0 0))", "Error constructing Polygon: shell is empty but holes are not");
        assertInvalidReason("POLYGON ((0 0, 0 1, 0 1, 1 1, 1 0, 0 0), (2 2, 2 3, 3 3, 3 2, 2 2))", "Hole lies outside shell");
        assertInvalidReason("POLYGON ((0 0, 0 1, 1 1, 1 0, 0 0), (2 2, 2 3, 3 3, 3 2, 2 2))", "Hole lies outside shell");
        assertInvalidReason("POLYGON ((0 0, 0 1, 2 1, 1 1, 1 0, 0 0))", "Self-intersection");
        assertInvalidReason("POLYGON ((0 0, 0 1, 1 1, 1 0, 0 0), (0 1, 1 1, 0.5 0.5, 0 1))", "Self-intersection");
        assertInvalidReason("POLYGON ((0 0, 0 1, 1 1, 1 0, 0 0), (0 0, 0.5 0.7, 1 1, 0.5 0.4, 0 0))", "Interior is disconnected");
        assertInvalidReason("POLYGON ((0 0, -1 0.5, 0 1, 1 1, 1 0, 0 1, 0 0))", "Ring Self-intersection");
        assertInvalidReason("MULTIPOLYGON (((0 0, 0 1, 1 1, 1 0, 0 0)), ((0.5 0.5, 0.5 2, 2 2, 2 0.5, 0.5 0.5)))", "Self-intersection");
        assertInvalidReason("GEOMETRYCOLLECTION (POINT (1 2), POLYGON ((0 0, 0 1, 2 1, 1 1, 1 0, 0 0)))", "Self-intersection");

        // non-simple geometries
        assertInvalidReason("MULTIPOINT (1 2, 2 4, 3 6, 1 2)", "[MultiPoint] Repeated point: (1.0 2.0)");
        assertInvalidReason("LINESTRING (0 0, 1 1, 1 0, 0 1)", "[LineString] Self-intersection at or near: (0.5 0.5)");
        assertInvalidReason("MULTILINESTRING ((1 1, 5 1), (2 4, 4 0))", "[MultiLineString] Self-intersection at or near: (3.5 1.0)");
    }

    private void assertInvalidReason(String wkt, String reason)
    {
        assertFunction("geometry_invalid_reason(ST_GeometryFromText('" + wkt + "'))", VARCHAR, reason);
    }

    @Test
    public void testSTLength()
    {
        assertFunction("ST_Length(ST_GeometryFromText('LINESTRING EMPTY'))", DOUBLE, 0.0);
        assertFunction("ST_Length(ST_GeometryFromText('LINESTRING (0 0, 2 2)'))", DOUBLE, 2.8284271247461903);
        assertFunction("ST_Length(ST_GeometryFromText('MULTILINESTRING ((1 1, 5 1), (2 4, 4 4))'))", DOUBLE, 6.0);
        assertInvalidFunction("ST_Length(ST_GeometryFromText('POLYGON ((1 1, 1 4, 4 4, 4 1, 1 1))'))", "ST_Length only applies to LINE_STRING or MULTI_LINE_STRING. Input type is: POLYGON");
    }

    @Test
    public void testLineLocatePoint()
    {
        assertFunction("line_locate_point(ST_GeometryFromText('LINESTRING (0 0, 0 1)'), ST_Point(0, 0.2))", DOUBLE, 0.2);
        assertFunction("line_locate_point(ST_GeometryFromText('LINESTRING (0 0, 0 1)'), ST_Point(0, 0))", DOUBLE, 0.0);
        assertFunction("line_locate_point(ST_GeometryFromText('LINESTRING (0 0, 0 1)'), ST_Point(0, -1))", DOUBLE, 0.0);
        assertFunction("line_locate_point(ST_GeometryFromText('LINESTRING (0 0, 0 1)'), ST_Point(0, 1))", DOUBLE, 1.0);
        assertFunction("line_locate_point(ST_GeometryFromText('LINESTRING (0 0, 0 1)'), ST_Point(0, 2))", DOUBLE, 1.0);
        assertFunction("line_locate_point(ST_GeometryFromText('LINESTRING (0 0, 0 1, 2 1)'), ST_Point(0, 0.2))", DOUBLE, 0.06666666666666667);
        assertFunction("line_locate_point(ST_GeometryFromText('LINESTRING (0 0, 0 1, 2 1)'), ST_Point(0.9, 1))", DOUBLE, 0.6333333333333333);
        assertFunction("line_locate_point(ST_GeometryFromText('LINESTRING (1 3, 5 4)'), ST_Point(1, 3))", DOUBLE, 0.0);
        assertFunction("line_locate_point(ST_GeometryFromText('LINESTRING (1 3, 5 4)'), ST_Point(2, 3))", DOUBLE, 0.23529411764705882);
        assertFunction("line_locate_point(ST_GeometryFromText('LINESTRING (1 3, 5 4)'), ST_Point(5, 4))", DOUBLE, 1.0);
        assertFunction("line_locate_point(ST_GeometryFromText('MULTILINESTRING ((0 0, 0 1), (2 2, 4 2))'), ST_Point(3, 1))", DOUBLE, 0.6666666666666666);

        assertFunction("line_locate_point(ST_GeometryFromText('LINESTRING EMPTY'), ST_Point(0, 1))", DOUBLE, null);
        assertFunction("line_locate_point(ST_GeometryFromText('LINESTRING (0 0, 0 1, 2 1)'), ST_GeometryFromText('POINT EMPTY'))", DOUBLE, null);

        assertInvalidFunction("line_locate_point(ST_GeometryFromText('POLYGON ((1 1, 1 4, 4 4, 4 1, 1 1))'), ST_Point(0.4, 1))", "First argument to line_locate_point must be a LineString or a MultiLineString. Got: Polygon");
        assertInvalidFunction("line_locate_point(ST_GeometryFromText('LINESTRING (0 0, 0 1, 2 1)'), ST_GeometryFromText('POLYGON ((1 1, 1 4, 4 4, 4 1, 1 1))'))", "Second argument to line_locate_point must be a Point. Got: Polygon");
    }

    @Test
    public void testLineInterpolatePoint()
    {
        assertLineInterpolatePoint("LINESTRING EMPTY", 0.5, "POINT EMPTY");
        assertLineInterpolatePoint("LINESTRING (0 0, 0 1)", 0.2, "POINT (0 0.2)");
        assertLineInterpolatePoint("LINESTRING (0 0, 0 1)", 0.0, "POINT (0 0)");
        assertLineInterpolatePoint("LINESTRING (0 0, 0 1)", 1.0, "POINT (0 1)");
        assertLineInterpolatePoint("LINESTRING (0 0, 0 1, 3 1)", 0.0625, "POINT (0 0.25)");
        assertLineInterpolatePoint("LINESTRING (0 0, 0 1, 3 1)", 0.75, "POINT (2 1)");
        assertLineInterpolatePoint("LINESTRING (1 3, 5 4)", 0.0, "POINT (1 3)");
        assertLineInterpolatePoint("LINESTRING (1 3, 5 4)", 0.25, "POINT (2 3.25)");
        assertLineInterpolatePoint("LINESTRING (1 3, 5 4)", 1.0, "POINT (5 4)");

        assertInvalidFunction("line_interpolate_point(ST_GeometryFromText('POLYGON ((1 1, 1 4, 4 4, 4 1, 1 1))'), 0.5)", "line_interpolate_point only applies to LINE_STRING. Input type is: POLYGON");
        assertInvalidFunction("line_interpolate_point(ST_GeometryFromText('MULTILINESTRING ((0 0, 0 1), (2 2, 4 2))'), 0.0)", "line_interpolate_point only applies to LINE_STRING. Input type is: MULTI_LINE_STRING");
        assertInvalidFunction("line_interpolate_point(ST_GeometryFromText('LINESTRING (0 0, 0 1, 2 1)'), -1)", "line_interpolate_point: Fraction must be between 0 and 1, but is -1.0");
        assertInvalidFunction("line_interpolate_point(ST_GeometryFromText('LINESTRING (0 0, 0 1, 2 1)'), 1.5)", "line_interpolate_point: Fraction must be between 0 and 1, but is 1.5");
    }

    private void assertLineInterpolatePoint(String sourceWkt, double distance, String expectedWkt)
    {
        assertFunction(format("ST_AsText(line_interpolate_point(ST_GeometryFromText('%s'), %s))", sourceWkt, distance), VARCHAR, expectedWkt);
    }

    @Test
    public void testSTMax()
    {
        assertFunction("ST_XMax(ST_GeometryFromText('POINT (1.5 2.5)'))", DOUBLE, 1.5);
        assertFunction("ST_YMax(ST_GeometryFromText('POINT (1.5 2.5)'))", DOUBLE, 2.5);
        assertFunction("ST_XMax(ST_GeometryFromText('MULTIPOINT (1 2, 2 4, 3 6, 4 8)'))", DOUBLE, 4.0);
        assertFunction("ST_YMax(ST_GeometryFromText('MULTIPOINT (1 2, 2 4, 3 6, 4 8)'))", DOUBLE, 8.0);
        assertFunction("ST_XMax(ST_GeometryFromText('LINESTRING (8 4, 5 7)'))", DOUBLE, 8.0);
        assertFunction("ST_YMax(ST_GeometryFromText('LINESTRING (8 4, 5 7)'))", DOUBLE, 7.0);
        assertFunction("ST_XMax(ST_GeometryFromText('MULTILINESTRING ((1 1, 5 1), (2 4, 4 4))'))", DOUBLE, 5.0);
        assertFunction("ST_YMax(ST_GeometryFromText('MULTILINESTRING ((1 1, 5 1), (2 4, 4 4))'))", DOUBLE, 4.0);
        assertFunction("ST_XMax(ST_GeometryFromText('POLYGON ((2 0, 2 1, 3 1, 2 0))'))", DOUBLE, 3.0);
        assertFunction("ST_YMax(ST_GeometryFromText('POLYGON ((2 0, 2 1, 3 1, 2 0))'))", DOUBLE, 1.0);
        assertFunction("ST_XMax(ST_GeometryFromText('MULTIPOLYGON (((1 1, 1 3, 3 3, 3 1, 1 1)), ((2 4, 2 6, 6 6, 6 4, 2 4)))'))", DOUBLE, 6.0);
        assertFunction("ST_YMax(ST_GeometryFromText('MULTIPOLYGON (((1 1, 1 3, 3 3, 3 1, 1 1)), ((2 4, 2 6, 6 10, 6 4, 2 4)))'))", DOUBLE, 10.0);
        assertFunction("ST_XMax(ST_GeometryFromText('POLYGON EMPTY'))", DOUBLE, null);
        assertFunction("ST_YMax(ST_GeometryFromText('POLYGON EMPTY'))", DOUBLE, null);
        assertFunction("ST_XMax(ST_GeometryFromText('GEOMETRYCOLLECTION (POINT (5 1), LINESTRING (3 4, 4 4))'))", DOUBLE, 5.0);
        assertFunction("ST_YMax(ST_GeometryFromText('GEOMETRYCOLLECTION (POINT (5 1), LINESTRING (3 4, 4 4))'))", DOUBLE, 4.0);
        assertFunction("ST_XMax(null)", DOUBLE, null);
        assertFunction("ST_YMax(null)", DOUBLE, null);
    }

    @Test
    public void testSTMin()
    {
        assertFunction("ST_XMin(ST_GeometryFromText('POINT (1.5 2.5)'))", DOUBLE, 1.5);
        assertFunction("ST_YMin(ST_GeometryFromText('POINT (1.5 2.5)'))", DOUBLE, 2.5);
        assertFunction("ST_XMin(ST_GeometryFromText('MULTIPOINT (1 2, 2 4, 3 6, 4 8)'))", DOUBLE, 1.0);
        assertFunction("ST_YMin(ST_GeometryFromText('MULTIPOINT (1 2, 2 4, 3 6, 4 8)'))", DOUBLE, 2.0);
        assertFunction("ST_XMin(ST_GeometryFromText('LINESTRING (8 4, 5 7)'))", DOUBLE, 5.0);
        assertFunction("ST_YMin(ST_GeometryFromText('LINESTRING (8 4, 5 7)'))", DOUBLE, 4.0);
        assertFunction("ST_XMin(ST_GeometryFromText('MULTILINESTRING ((1 1, 5 1), (2 4, 4 4))'))", DOUBLE, 1.0);
        assertFunction("ST_YMin(ST_GeometryFromText('MULTILINESTRING ((1 2, 5 3), (2 4, 4 4))'))", DOUBLE, 2.0);
        assertFunction("ST_XMin(ST_GeometryFromText('POLYGON ((2 0, 2 1, 3 1, 2 0))'))", DOUBLE, 2.0);
        assertFunction("ST_YMin(ST_GeometryFromText('POLYGON ((2 0, 2 1, 3 1, 2 0))'))", DOUBLE, 0.0);
        assertFunction("ST_XMin(ST_GeometryFromText('MULTIPOLYGON (((1 10, 1 3, 3 3, 3 10, 1 10)), ((2 4, 2 6, 6 6, 6 4, 2 4)))'))", DOUBLE, 1.0);
        assertFunction("ST_YMin(ST_GeometryFromText('MULTIPOLYGON (((1 10, 1 3, 3 3, 3 10, 1 10)), ((2 4, 2 6, 6 10, 6 4, 2 4)))'))", DOUBLE, 3.0);
        assertFunction("ST_XMin(ST_GeometryFromText('POLYGON EMPTY'))", DOUBLE, null);
        assertFunction("ST_YMin(ST_GeometryFromText('POLYGON EMPTY'))", DOUBLE, null);
        assertFunction("ST_XMin(ST_GeometryFromText('GEOMETRYCOLLECTION (POINT (5 1), LINESTRING (3 4, 4 4))'))", DOUBLE, 3.0);
        assertFunction("ST_YMin(ST_GeometryFromText('GEOMETRYCOLLECTION (POINT (5 1), LINESTRING (3 4, 4 4))'))", DOUBLE, 1.0);
        assertFunction("ST_XMin(null)", DOUBLE, null);
        assertFunction("ST_YMin(null)", DOUBLE, null);
    }

    @Test
    public void testSTNumInteriorRing()
    {
        assertFunction("ST_NumInteriorRing(ST_GeometryFromText('POLYGON ((0 0, 0 5, 5 5, 5 0, 0 0))'))", BIGINT, 0L);
        assertFunction("ST_NumInteriorRing(ST_GeometryFromText('POLYGON ((0 0, 8 0, 0 8, 0 0), (1 1, 1 5, 5 1, 1 1))'))", BIGINT, 1L);
        assertInvalidFunction("ST_NumInteriorRing(ST_GeometryFromText('LINESTRING (8 4, 5 7)'))", "ST_NumInteriorRing only applies to POLYGON. Input type is: LINE_STRING");
    }

    @Test
    public void testSTNumPoints()
    {
        assertNumPoints("POINT EMPTY", 0);
        assertNumPoints("MULTIPOINT EMPTY", 0);
        assertNumPoints("LINESTRING EMPTY", 0);
        assertNumPoints("MULTILINESTRING EMPTY", 0);
        assertNumPoints("POLYGON EMPTY", 0);
        assertNumPoints("MULTIPOLYGON EMPTY", 0);
        assertNumPoints("GEOMETRYCOLLECTION EMPTY", 0);

        assertNumPoints("POINT (1 2)", 1);
        assertNumPoints("MULTIPOINT (1 2, 2 4, 3 6, 4 8)", 4);
        assertNumPoints("LINESTRING (8 4, 5 7)", 2);
        assertNumPoints("MULTILINESTRING ((1 1, 5 1), (2 4, 4 4))", 4);
        assertNumPoints("POLYGON ((0 0, 8 0, 0 8, 0 0), (1 1, 1 5, 5 1, 1 1))", 6);
        assertNumPoints("MULTIPOLYGON (((1 1, 1 3, 3 3, 3 1, 1 1)), ((2 4, 2 6, 6 6, 6 4, 2 4)))", 8);
        assertNumPoints("GEOMETRYCOLLECTION (POINT (1 2), LINESTRING (8 4, 5 7), POLYGON EMPTY)", 3);
    }

    private void assertNumPoints(String wkt, int expectedPoints)
    {
        assertFunction(format("ST_NumPoints(ST_GeometryFromText('%s'))", wkt), BIGINT, (long) expectedPoints);
    }

    @Test
    public void testSTIsRing()
    {
        assertFunction("ST_IsRing(ST_GeometryFromText('LINESTRING (8 4, 4 8)'))", BOOLEAN, false);
        assertFunction("ST_IsRing(ST_GeometryFromText('LINESTRING (0 0, 1 1, 0 2, 0 0)'))", BOOLEAN, true);
        assertInvalidFunction("ST_IsRing(ST_GeometryFromText('POLYGON ((2 0, 2 1, 3 1, 2 0))'))", "ST_IsRing only applies to LINE_STRING. Input type is: POLYGON");
    }

    @Test
    public void testSTStartEndPoint()
    {
        assertFunction("ST_AsText(ST_StartPoint(ST_GeometryFromText('LINESTRING (8 4, 4 8, 5 6)')))", VARCHAR, "POINT (8 4)");
        assertFunction("ST_AsText(ST_EndPoint(ST_GeometryFromText('LINESTRING (8 4, 4 8, 5 6)')))", VARCHAR, "POINT (5 6)");
        assertInvalidFunction("ST_StartPoint(ST_GeometryFromText('POLYGON ((2 0, 2 1, 3 1, 2 0))'))", "ST_StartPoint only applies to LINE_STRING. Input type is: POLYGON");
        assertInvalidFunction("ST_EndPoint(ST_GeometryFromText('POLYGON ((2 0, 2 1, 3 1, 2 0))'))", "ST_EndPoint only applies to LINE_STRING. Input type is: POLYGON");
    }

    @Test
    public void testSTPoints()
    {
        assertFunction("ST_Points(ST_GeometryFromText('LINESTRING EMPTY'))", new ArrayType(GEOMETRY), null);
        assertSTPoints("LINESTRING (0 0, 0 0)", "0 0", "0 0");
        assertSTPoints("LINESTRING (8 4, 3 9, 8 4)", "8 4", "3 9", "8 4");
        assertSTPoints("LINESTRING (8 4, 3 9, 5 6)", "8 4", "3 9", "5 6");
        assertSTPoints("LINESTRING (8 4, 3 9, 5 6, 3 9, 8 4)", "8 4", "3 9", "5 6", "3 9", "8 4");

        assertFunction("ST_Points(ST_GeometryFromText('POLYGON EMPTY'))", new ArrayType(GEOMETRY), null);
        assertSTPoints("POLYGON ((8 4, 3 9, 5 6, 8 4))", "8 4", "5 6", "3 9", "8 4");
        assertSTPoints("POLYGON ((8 4, 3 9, 5 6, 7 2, 8 4))", "8 4", "7 2", "5 6", "3 9", "8 4");

        assertFunction("ST_Points(ST_GeometryFromText('POINT EMPTY'))", new ArrayType(GEOMETRY), null);
        assertSTPoints("POINT (0 0)", "0 0");
        assertSTPoints("POINT (0 1)", "0 1");

        assertFunction("ST_Points(ST_GeometryFromText('MULTIPOINT EMPTY'))", new ArrayType(GEOMETRY), null);
        assertSTPoints("MULTIPOINT (0 0)", "0 0");
        assertSTPoints("MULTIPOINT (0 0, 1 2)", "0 0", "1 2");

        assertFunction("ST_Points(ST_GeometryFromText('MULTILINESTRING EMPTY'))", new ArrayType(GEOMETRY), null);
        assertSTPoints("MULTILINESTRING ((0 0, 1 1), (2 3, 3 2))", "0 0", "1 1", "2 3", "3 2");
        assertSTPoints("MULTILINESTRING ((0 0, 1 1, 1 2), (2 3, 3 2, 5 4))", "0 0", "1 1", "1 2", "2 3", "3 2", "5 4");
        assertSTPoints("MULTILINESTRING ((0 0, 1 1, 1 2), (1 2, 3 2, 5 4))", "0 0", "1 1", "1 2", "1 2", "3 2", "5 4");

        assertFunction("ST_Points(ST_GeometryFromText('MULTIPOLYGON EMPTY'))", new ArrayType(GEOMETRY), null);
        assertSTPoints("MULTIPOLYGON (((0 0, 4 0, 4 4, 0 4, 0 0), (1 1, 2 1, 2 2, 1 2, 1 1)), ((-1 -1, -1 -2, -2 -2, -2 -1, -1 -1)))",
                "0 0", "0 4", "4 4", "4 0", "0 0",
                "1 1", "2 1", "2 2", "1 2", "1 1",
                "-1 -1", "-1 -2", "-2 -2", "-2 -1", "-1 -1");

        assertFunction("ST_Points(ST_GeometryFromText('GEOMETRYCOLLECTION EMPTY'))", new ArrayType(GEOMETRY), null);
        String geometryCollection = String.join("",
                "GEOMETRYCOLLECTION(",
                "          POINT ( 0 1 ),",
                "          LINESTRING ( 0 3, 3 4 ),",
                "          POLYGON (( 2 0, 2 3, 0 2, 2 0 )),",
                "          POLYGON (( 3 0, 3 3, 6 3, 6 0, 3 0 ),",
                "                   ( 5 1, 4 2, 5 2, 5 1 )),",
                "          MULTIPOLYGON (",
                "                  (( 0 5, 0 8, 4 8, 4 5, 0 5 ),",
                "                   ( 1 6, 3 6, 2 7, 1 6 )),",
                "                  (( 5 4, 5 8, 6 7, 5 4 ))",
                "           )",
                ")");
        assertSTPoints(geometryCollection, "0 1", "0 3", "3 4", "2 0", "0 2", "2 3", "2 0", "3 0", "3 3", "6 3", "6 0", "3 0",
                "5 1", "5 2", "4 2", "5 1", "0 5", "0 8", "4 8", "4 5", "0 5", "1 6", "3 6", "2 7", "1 6", "5 4", "5 8", "6 7", "5 4");
    }

    private void assertSTPoints(String wkt, String... expected)
    {
        assertFunction(String.format("transform(ST_Points(ST_GeometryFromText('%s')), x -> ST_AsText(x))", wkt), new ArrayType(VARCHAR),
                Arrays.stream(expected).map(s -> "POINT (" + s + ")").collect(Collectors.toList()));
    }

    @Test
    public void testSTXY()
    {
        assertFunction("ST_Y(ST_GeometryFromText('POINT EMPTY'))", DOUBLE, null);
        assertFunction("ST_X(ST_GeometryFromText('POINT (1 2)'))", DOUBLE, 1.0);
        assertFunction("ST_Y(ST_GeometryFromText('POINT (1 2)'))", DOUBLE, 2.0);
        assertInvalidFunction("ST_Y(ST_GeometryFromText('POLYGON ((2 0, 2 1, 3 1, 2 0))'))", "ST_Y only applies to POINT. Input type is: POLYGON");
    }

    @Test
    public void testSTBoundary()
    {
        assertFunction("ST_AsText(ST_Boundary(ST_GeometryFromText('POINT (1 2)')))", VARCHAR, "GEOMETRYCOLLECTION EMPTY");
        assertFunction("ST_AsText(ST_Boundary(ST_GeometryFromText('MULTIPOINT (1 2, 2 4, 3 6, 4 8)')))", VARCHAR, "GEOMETRYCOLLECTION EMPTY");
        assertFunction("ST_AsText(ST_Boundary(ST_GeometryFromText('LINESTRING EMPTY')))", VARCHAR, "MULTIPOINT EMPTY");
        assertFunction("ST_AsText(ST_Boundary(ST_GeometryFromText('LINESTRING (8 4, 5 7)')))", VARCHAR, "MULTIPOINT ((8 4), (5 7))");
        assertFunction("ST_AsText(ST_Boundary(ST_GeometryFromText('LINESTRING (100 150,50 60, 70 80, 160 170)')))", VARCHAR, "MULTIPOINT ((100 150), (160 170))");
        assertFunction("ST_AsText(ST_Boundary(ST_GeometryFromText('MULTILINESTRING ((1 1, 5 1), (2 4, 4 4))')))", VARCHAR, "MULTIPOINT ((1 1), (2 4), (4 4), (5 1))");
        assertFunction("ST_AsText(ST_Boundary(ST_GeometryFromText('POLYGON ((1 1, 4 1, 1 4, 1 1))')))", VARCHAR, "LINESTRING (1 1, 1 4, 4 1, 1 1)");
        assertFunction("ST_AsText(ST_Boundary(ST_GeometryFromText('MULTIPOLYGON (((1 1, 1 3, 3 3, 3 1, 1 1)), ((0 0, 0 2, 2 2, 2 0, 0 0)))')))", VARCHAR, "MULTILINESTRING ((1 1, 1 3, 3 3, 3 1, 1 1), (0 0, 0 2, 2 2, 2 0, 0 0))");
    }

    @Test
    public void testSTEnvelope()
    {
        assertFunction("ST_AsText(ST_Envelope(ST_GeometryFromText('MULTIPOINT (1 2, 2 4, 3 6, 4 8)')))", VARCHAR, "POLYGON ((1 2, 1 8, 4 8, 4 2, 1 2))");
        assertFunction("ST_AsText(ST_Envelope(ST_GeometryFromText('LINESTRING EMPTY')))", VARCHAR, "POLYGON EMPTY");
        assertFunction("ST_AsText(ST_Envelope(ST_GeometryFromText('LINESTRING (1 1, 2 2, 1 3)')))", VARCHAR, "POLYGON ((1 1, 1 3, 2 3, 2 1, 1 1))");
        assertFunction("ST_AsText(ST_Envelope(ST_GeometryFromText('LINESTRING (8 4, 5 7)')))", VARCHAR, "POLYGON ((5 4, 5 7, 8 7, 8 4, 5 4))");
        assertFunction("ST_AsText(ST_Envelope(ST_GeometryFromText('MULTILINESTRING ((1 1, 5 1), (2 4, 4 4))')))", VARCHAR, "POLYGON ((1 1, 1 4, 5 4, 5 1, 1 1))");
        assertFunction("ST_AsText(ST_Envelope(ST_GeometryFromText('POLYGON ((1 1, 4 1, 1 4, 1 1))')))", VARCHAR, "POLYGON ((1 1, 1 4, 4 4, 4 1, 1 1))");
        assertFunction("ST_AsText(ST_Envelope(ST_GeometryFromText('MULTIPOLYGON (((1 1, 1 3, 3 3, 3 1, 1 1)), ((0 0, 0 2, 2 2, 2 0, 0 0)))')))", VARCHAR, "POLYGON ((0 0, 0 3, 3 3, 3 0, 0 0))");
        assertFunction("ST_AsText(ST_Envelope(ST_GeometryFromText('GEOMETRYCOLLECTION (POINT (5 1), LINESTRING (3 4, 4 4))')))", VARCHAR, "POLYGON ((3 1, 3 4, 5 4, 5 1, 3 1))");
    }

    @Test
    public void testSTEnvelopeAsPts()
    {
        assertEnvelopeAsPts("MULTIPOINT (1 2, 2 4, 3 6, 4 8)", new Point(1, 2), new Point(4, 8));
        assertFunction("ST_EnvelopeAsPts(ST_GeometryFromText('LINESTRING EMPTY'))", new ArrayType(GEOMETRY), null);
        assertEnvelopeAsPts("LINESTRING (1 1, 2 2, 1 3)", new Point(1, 1), new Point(2, 3));
        assertEnvelopeAsPts("LINESTRING (8 4, 5 7)", new Point(5, 4), new Point(8, 7));
        assertEnvelopeAsPts("MULTILINESTRING ((1 1, 5 1), (2 4, 4 4))", new Point(1, 1), new Point(5, 4));
        assertEnvelopeAsPts("POLYGON ((1 1, 4 1, 1 4, 1 1))", new Point(1, 1), new Point(4, 4));
        assertEnvelopeAsPts("MULTIPOLYGON (((1 1, 1 3, 3 3, 3 1, 1 1)), ((0 0, 0 2, 2 2, 2 0, 0 0)))", new Point(0, 0), new Point(3, 3));
        assertEnvelopeAsPts("GEOMETRYCOLLECTION (POINT (5 1), LINESTRING (3 4, 4 4))", new Point(3, 1), new Point(5, 4));
        assertEnvelopeAsPts("POINT (1 2)", new Point(1, 2), new Point(1, 2));
    }

    private void assertEnvelopeAsPts(String wkt, Point lowerLeftCorner, Point upperRightCorner)
    {
        assertFunction(format("transform(ST_EnvelopeAsPts(ST_GeometryFromText('%s')), x -> ST_AsText(x))", wkt), new ArrayType(VARCHAR), ImmutableList.of(new OGCPoint(lowerLeftCorner, null).asText(), new OGCPoint(upperRightCorner, null).asText()));
    }

    @Test
    public void testExpandEnvelope()
    {
        assertFunction("ST_IsEmpty(expand_envelope(ST_GeometryFromText('POINT EMPTY'), 1))", BOOLEAN, true);
        assertFunction("ST_IsEmpty(expand_envelope(ST_GeometryFromText('POLYGON EMPTY'), 1))", BOOLEAN, true);
        assertFunction("ST_AsText(expand_envelope(ST_Envelope(ST_Point(1, 10)), 3))", VARCHAR, "POLYGON ((-2 7, -2 13, 4 13, 4 7, -2 7))");
        assertFunction("ST_AsText(expand_envelope(ST_Point(1, 10), 3))", VARCHAR, "POLYGON ((-2 7, -2 13, 4 13, 4 7, -2 7))");
        assertFunction("ST_AsText(expand_envelope(ST_GeometryFromText('LINESTRING (1 10, 3 15)'), 2))", VARCHAR, "POLYGON ((-1 8, -1 17, 5 17, 5 8, -1 8))");
        assertFunction("ST_AsText(expand_envelope(ST_GeometryFromText('GEOMETRYCOLLECTION (POINT (5 1), LINESTRING (3 4, 4 4))'), 1))", VARCHAR, "POLYGON ((2 0, 2 5, 6 5, 6 0, 2 0))");
        // JTS has an envelope expanded by infinity to be empty, which is weird.
        // PostGIS returns an infinite envelope, which is a tricky concept.
        // We'll leave it like this until it becomes a problem.
        assertFunction("ST_AsText(expand_envelope(ST_Point(0, 0), infinity()))", VARCHAR, "POLYGON EMPTY");
        assertInvalidFunction("ST_AsText(expand_envelope(ST_Point(0, 0), nan()))", "expand_envelope: distance is NaN");
        assertInvalidFunction("ST_AsText(expand_envelope(ST_Point(0, 0), -1))", "expand_envelope: distance -1.0 is negative");
        assertInvalidFunction("ST_AsText(expand_envelope(ST_Point(0, 0), -infinity()))", "expand_envelope: distance -Infinity is negative");
    }

    @Test
    public void testSTDifference()
    {
        assertFunction("ST_AsText(ST_Difference(ST_GeometryFromText('POINT (50 100)'), ST_GeometryFromText('POINT (150 150)')))", VARCHAR, "POINT (50 100)");
        assertFunction("ST_AsText(ST_Difference(ST_GeometryFromText('MULTIPOINT (50 100, 50 200)'), ST_GeometryFromText('POINT (50 100)')))", VARCHAR, "POINT (50 200)");
        assertFunction("ST_AsText(ST_Difference(ST_GeometryFromText('LINESTRING (50 100, 50 200)'), ST_GeometryFromText('LINESTRING (50 50, 50 150)')))", VARCHAR, "LINESTRING (50 150, 50 200)");
        assertFunction("ST_AsText(ST_Difference(ST_GeometryFromText('MULTILINESTRING ((1 1, 5 1), (2 4, 4 4))'), ST_GeometryFromText('MULTILINESTRING ((2 1, 4 1), (3 3, 7 3))')))", VARCHAR, "MULTILINESTRING ((1 1, 2 1), (4 1, 5 1), (2 4, 4 4))");
        assertFunction("ST_AsText(ST_Difference(ST_GeometryFromText('POLYGON ((1 1, 1 4, 4 4, 4 1, 1 1))'), ST_GeometryFromText('POLYGON ((2 2, 2 5, 5 5, 5 2, 2 2))')))", VARCHAR, "POLYGON ((1 1, 1 4, 2 4, 2 2, 4 2, 4 1, 1 1))");
        assertFunction("ST_AsText(ST_Difference(ST_GeometryFromText('MULTIPOLYGON (((1 1, 1 3, 3 3, 3 1, 1 1)), ((0 0, 0 2, 2 2, 2 0, 0 0)))'), ST_GeometryFromText('POLYGON ((0 1, 3 1, 3 3, 0 3, 0 1))')))", VARCHAR, "POLYGON ((1 1, 2 1, 2 0, 0 0, 0 1, 1 1))");
    }

    @Test
    public void testSTDistance()
    {
        assertFunction("ST_Distance(ST_Point(50, 100), ST_Point(150, 150))", DOUBLE, 111.80339887498948);
        assertFunction("ST_Distance(ST_Point(50, 100), ST_GeometryFromText('POINT (150 150)'))", DOUBLE, 111.80339887498948);
        assertFunction("ST_Distance(ST_GeometryFromText('POINT (50 100)'), ST_GeometryFromText('POINT (150 150)'))", DOUBLE, 111.80339887498948);
        assertFunction("ST_Distance(ST_GeometryFromText('MULTIPOINT (50 100, 50 200)'), ST_GeometryFromText('Point (50 100)'))", DOUBLE, 0.0);
        assertFunction("ST_Distance(ST_GeometryFromText('LINESTRING (50 100, 50 200)'), ST_GeometryFromText('LINESTRING (10 10, 20 20)'))", DOUBLE, 85.44003745317531);
        assertFunction("ST_Distance(ST_GeometryFromText('MULTILINESTRING ((1 1, 5 1), (2 4, 4 4))'), ST_GeometryFromText('LINESTRING (10 20, 20 50)'))", DOUBLE, 17.08800749063506);
        assertFunction("ST_Distance(ST_GeometryFromText('POLYGON ((1 1, 1 3, 3 3, 3 1, 1 1))'), ST_GeometryFromText('POLYGON ((4 4, 4 5, 5 5, 5 4, 4 4))'))", DOUBLE, 1.4142135623730951);
        assertFunction("ST_Distance(ST_GeometryFromText('MULTIPOLYGON (((1 1, 1 3, 3 3, 3 1, 1 1)), ((0 0, 0 2, 2 2, 2 0, 0 0)))'), ST_GeometryFromText('POLYGON ((10 100, 30 10, 30 100, 10 100))'))", DOUBLE, 27.892651361962706);

        assertFunction("ST_Distance(ST_GeometryFromText('POINT EMPTY'), ST_Point(150, 150))", DOUBLE, null);
        assertFunction("ST_Distance(ST_Point(50, 100), ST_GeometryFromText('POINT EMPTY'))", DOUBLE, null);
        assertFunction("ST_Distance(ST_GeometryFromText('POINT EMPTY'), ST_GeometryFromText('POINT EMPTY'))", DOUBLE, null);
        assertFunction("ST_Distance(ST_GeometryFromText('MULTIPOINT EMPTY'), ST_GeometryFromText('Point (50 100)'))", DOUBLE, null);
        assertFunction("ST_Distance(ST_GeometryFromText('LINESTRING (50 100, 50 200)'), ST_GeometryFromText('LINESTRING EMPTY'))", DOUBLE, null);
        assertFunction("ST_Distance(ST_GeometryFromText('MULTILINESTRING EMPTY'), ST_GeometryFromText('LINESTRING (10 20, 20 50)'))", DOUBLE, null);
        assertFunction("ST_Distance(ST_GeometryFromText('POLYGON ((1 1, 1 3, 3 3, 3 1, 1 1))'), ST_GeometryFromText('POLYGON EMPTY'))", DOUBLE, null);
        assertFunction("ST_Distance(ST_GeometryFromText('MULTIPOLYGON EMPTY'), ST_GeometryFromText('POLYGON ((10 100, 30 10, 30 100, 10 100))'))", DOUBLE, null);
    }

    @Test
    public void testSTExteriorRing()
    {
        assertFunction("ST_AsText(ST_ExteriorRing(ST_GeometryFromText('POLYGON EMPTY')))", VARCHAR, null);
        assertFunction("ST_AsText(ST_ExteriorRing(ST_GeometryFromText('POLYGON ((1 1, 1 4, 4 1, 1 1))')))", VARCHAR, "LINESTRING (1 1, 1 4, 4 1, 1 1)");
        assertFunction("ST_AsText(ST_ExteriorRing(ST_GeometryFromText('POLYGON ((0 0, 0 5, 5 5, 5 0, 0 0), (1 1, 1 2, 2 2, 2 1, 1 1))')))", VARCHAR, "LINESTRING (0 0, 0 5, 5 5, 5 0, 0 0)");
        assertInvalidFunction("ST_AsText(ST_ExteriorRing(ST_GeometryFromText('LINESTRING (1 1, 2 2, 1 3)')))", "ST_ExteriorRing only applies to POLYGON. Input type is: LINE_STRING");
        assertInvalidFunction("ST_AsText(ST_ExteriorRing(ST_GeometryFromText('MULTIPOLYGON (((1 1, 2 2, 1 3, 1 1)), ((4 4, 5 5, 4 6, 4 4)))')))", "ST_ExteriorRing only applies to POLYGON. Input type is: MULTI_POLYGON");
    }

    @Test
    public void testSTIntersection()
    {
        assertFunction("ST_AsText(ST_Intersection(ST_GeometryFromText('POINT (50 100)'), ST_GeometryFromText('POINT (150 150)')))", VARCHAR, "MULTIPOLYGON EMPTY");
        assertFunction("ST_AsText(ST_Intersection(ST_GeometryFromText('MULTIPOINT (50 100, 50 200)'), ST_GeometryFromText('Point (50 100)')))", VARCHAR, "POINT (50 100)");
        assertFunction("ST_AsText(ST_Intersection(ST_GeometryFromText('LINESTRING (50 100, 50 200)'), ST_GeometryFromText('LINESTRING (20 150, 100 150)')))", VARCHAR, "POINT (50 150)");
        assertFunction("ST_AsText(ST_Intersection(ST_GeometryFromText('MULTILINESTRING ((1 1, 5 1), (2 4, 4 4))'), ST_GeometryFromText('MULTILINESTRING ((3 4, 6 4), (5 0, 5 4))')))", VARCHAR, "GEOMETRYCOLLECTION (POINT (5 1), LINESTRING (3 4, 4 4))");
        assertFunction("ST_AsText(ST_Intersection(ST_GeometryFromText('POLYGON ((1 1, 1 3, 3 3, 3 1, 1 1))'), ST_GeometryFromText('POLYGON ((4 4, 4 5, 5 5, 5 4, 4 4))')))", VARCHAR, "MULTIPOLYGON EMPTY");
        assertFunction("ST_AsText(ST_Intersection(ST_GeometryFromText('MULTIPOLYGON (((1 1, 1 3, 3 3, 3 1, 1 1)), ((0 0, 0 2, 2 2, 2 0, 0 0)))'), ST_GeometryFromText('POLYGON ((0 1, 3 1, 3 3, 0 3, 0 1))')))", VARCHAR, "GEOMETRYCOLLECTION (LINESTRING (1 1, 2 1), MULTIPOLYGON (((0 1, 0 2, 1 2, 1 1, 0 1)), ((2 1, 2 2, 1 2, 1 3, 3 3, 3 1, 2 1))))");
        assertFunction("ST_AsText(ST_Intersection(ST_GeometryFromText('POLYGON ((1 1, 1 4, 4 4, 4 1, 1 1))'), ST_GeometryFromText('LINESTRING (2 0, 2 3)')))", VARCHAR, "LINESTRING (2 1, 2 3)");
        assertFunction("ST_AsText(ST_Intersection(ST_GeometryFromText('POLYGON ((0 0, 0 1, 1 1, 1 0, 0 0))'), ST_GeometryFromText('LINESTRING (0 0, 1 -1, 1 2)')))", VARCHAR, "GEOMETRYCOLLECTION (POINT (0 0), LINESTRING (1 0, 1 1))");

        // test intersection of envelopes
        assertEnvelopeIntersection("POLYGON ((0 0, 0 5, 5 5, 5 0, 0 0))", "POLYGON ((0 0, 0 5, 5 5, 5 0, 0 0))", "POLYGON ((0 0, 0 5, 5 5, 5 0, 0 0))");
        assertEnvelopeIntersection("POLYGON ((0 0, 0 5, 5 5, 5 0, 0 0))", "POLYGON ((-1 4, 1 4, 1 6, -1 6, -1 4))", "POLYGON ((0 4, 0 5, 1 5, 1 4, 0 4))");
        assertEnvelopeIntersection("POLYGON ((0 0, 0 5, 5 5, 5 0, 0 0))", "POLYGON ((1 4, 2 4, 2 6, 1 6, 1 4))", "POLYGON ((1 4, 1 5, 2 5, 2 4, 1 4))");
        assertEnvelopeIntersection("POLYGON ((0 0, 0 5, 5 5, 5 0, 0 0))", "POLYGON ((4 4, 6 4, 6 6, 4 6, 4 4))", "POLYGON ((4 4, 4 5, 5 5, 5 4, 4 4))");
        assertEnvelopeIntersection("POLYGON ((0 0, 0 5, 5 5, 5 0, 0 0))", "POLYGON ((10 10, 11 10, 11 11, 10 11, 10 10))", "POLYGON EMPTY");
        assertEnvelopeIntersection("POLYGON ((0 0, 0 5, 5 5, 5 0, 0 0))", "POLYGON ((-1 -1, 0 -1, 0 1, -1 1, -1 -1))", "LINESTRING (0 0, 0 1)");
        assertEnvelopeIntersection("POLYGON ((0 0, 0 5, 5 5, 5 0, 0 0))", "POLYGON ((1 -1, 2 -1, 2 0, 1 0, 1 -1))", "LINESTRING (1 0, 2 0)");
        assertEnvelopeIntersection("POLYGON ((0 0, 0 5, 5 5, 5 0, 0 0))", "POLYGON ((-1 -1, 0 -1, 0 0, -1 0, -1 -1))", "POINT (0 0)");
    }

    private void assertEnvelopeIntersection(String envelope, String otherEnvelope, String intersection)
    {
        assertFunction("ST_AsText(ST_Intersection(ST_Envelope(ST_GeometryFromText('" + envelope + "')), ST_Envelope(ST_GeometryFromText('" + otherEnvelope + "'))))", VARCHAR, intersection);
    }

    @Test
    public void testSTSymmetricDifference()
    {
        assertFunction("ST_AsText(ST_SymDifference(ST_GeometryFromText('POINT (50 100)'), ST_GeometryFromText('POINT (50 150)')))", VARCHAR, "MULTIPOINT ((50 100), (50 150))");
        assertFunction("ST_AsText(ST_SymDifference(ST_GeometryFromText('MULTIPOINT (50 100, 60 200)'), ST_GeometryFromText('MULTIPOINT (60 200, 70 150)')))", VARCHAR, "MULTIPOINT ((50 100), (70 150))");
        assertFunction("ST_AsText(ST_SymDifference(ST_GeometryFromText('LINESTRING (50 100, 50 200)'), ST_GeometryFromText('LINESTRING (50 50, 50 150)')))", VARCHAR, "MULTILINESTRING ((50 50, 50 100), (50 150, 50 200))");
        assertFunction("ST_AsText(ST_SymDifference(ST_GeometryFromText('MULTILINESTRING ((1 1, 5 1), (2 4, 4 4))'), ST_GeometryFromText('MULTILINESTRING ((3 4, 6 4), (5 0, 5 4))')))", VARCHAR, "MULTILINESTRING ((5 0, 5 1), (1 1, 5 1), (5 1, 5 4), (2 4, 3 4), (4 4, 5 4), (5 4, 6 4))");
        assertFunction("ST_AsText(ST_SymDifference(ST_GeometryFromText('POLYGON ((1 1, 1 4, 4 4, 4 1, 1 1))'), ST_GeometryFromText('POLYGON ((2 2, 2 5, 5 5, 5 2, 2 2))')))", VARCHAR, "MULTIPOLYGON (((1 1, 1 4, 2 4, 2 2, 4 2, 4 1, 1 1)), ((4 2, 4 4, 2 4, 2 5, 5 5, 5 2, 4 2)))");
        assertFunction("ST_AsText(ST_SymDifference(ST_GeometryFromText('MULTIPOLYGON (((0 0, 0 2, 2 2, 2 0, 0 0)), ((2 2, 2 4, 4 4, 4 2, 2 2)))'), ST_GeometryFromText('POLYGON ((0 0, 0 3, 3 3, 3 0, 0 0))')))", VARCHAR, "MULTIPOLYGON (((2 0, 2 2, 3 2, 3 0, 2 0)), ((0 2, 0 3, 2 3, 2 2, 0 2)), ((3 2, 3 3, 2 3, 2 4, 4 4, 4 2, 3 2)))");
    }

    @Test
    public void testSTInteriorRings()
    {
        assertInvalidInteriorRings("POINT (2 3)", "POINT");
        assertInvalidInteriorRings("LINESTRING EMPTY", "LINE_STRING");
        assertInvalidInteriorRings("MULTIPOINT (30 20, 60 70)", "MULTI_POINT");
        assertInvalidInteriorRings("MULTILINESTRING ((1 10, 100 1000), (2 2, 1 0, 5 6))", "MULTI_LINE_STRING");
        assertInvalidInteriorRings("MULTIPOLYGON (((1 1, 1 3, 3 3, 3 1, 1 1)), ((0 0, 0 2, 2 2, 2 0, 0 0)))", "MULTI_POLYGON");
        assertInvalidInteriorRings("GEOMETRYCOLLECTION (POINT (1 1), POINT (2 3), LINESTRING (5 8, 13 21))", "GEOMETRY_COLLECTION");

        assertFunction("ST_InteriorRings(ST_GeometryFromText('POLYGON EMPTY'))", new ArrayType(GEOMETRY), null);
        assertInteriorRings("POLYGON ((0 0, 0 1, 1 1, 1 0, 0 0))");
        assertInteriorRings("POLYGON ((0 0, 0 3, 3 3, 3 0, 0 0), (1 1, 2 1, 2 2, 1 2, 1 1))", "LINESTRING (1 1, 2 1, 2 2, 1 2, 1 1)");
        assertInteriorRings("POLYGON ((0 0, 0 5, 5 5, 5 0, 0 0), (1 1, 2 1, 2 2, 1 2, 1 1), (3 3, 4 3, 4 4, 3 4, 3 3))",
                "LINESTRING (1 1, 2 1, 2 2, 1 2, 1 1)", "LINESTRING (3 3, 4 3, 4 4, 3 4, 3 3)");
    }

    private void assertInteriorRings(String wkt, String... expected)
    {
        assertFunction(format("transform(ST_InteriorRings(ST_GeometryFromText('%s')), x -> ST_ASText(x))", wkt), new ArrayType(VARCHAR), ImmutableList.copyOf(expected));
    }

    private void assertInvalidInteriorRings(String wkt, String geometryType)
    {
        assertInvalidFunction(format("ST_InteriorRings(ST_GeometryFromText('%s'))", wkt), format("ST_InteriorRings only applies to POLYGON. Input type is: %s", geometryType));
    }

    @Test
    public void testSTNumGeometries()
    {
        assertSTNumGeometries("POINT EMPTY", 0);
        assertSTNumGeometries("LINESTRING EMPTY", 0);
        assertSTNumGeometries("POLYGON EMPTY", 0);
        assertSTNumGeometries("MULTIPOINT EMPTY", 0);
        assertSTNumGeometries("MULTILINESTRING EMPTY", 0);
        assertSTNumGeometries("MULTIPOLYGON EMPTY", 0);
        assertSTNumGeometries("GEOMETRYCOLLECTION EMPTY", 0);
        assertSTNumGeometries("POINT (1 2)", 1);
        assertSTNumGeometries("LINESTRING(77.29 29.07,77.42 29.26,77.27 29.31,77.29 29.07)", 1);
        assertSTNumGeometries("POLYGON ((0 0, 0 1, 1 1, 1 0, 0 0))", 1);
        assertSTNumGeometries("MULTIPOINT (1 2, 2 4, 3 6, 4 8)", 4);
        assertSTNumGeometries("MULTILINESTRING ((1 1, 5 1), (2 4, 4 4))", 2);
        assertSTNumGeometries("MULTIPOLYGON (((1 1, 1 3, 3 3, 3 1, 1 1)), ((2 4, 2 6, 6 6, 6 4, 2 4)))", 2);
        assertSTNumGeometries("GEOMETRYCOLLECTION(POINT(2 3), LINESTRING (2 3, 3 4))", 2);
    }

    private void assertSTNumGeometries(String wkt, int expected)
    {
        assertFunction("ST_NumGeometries(ST_GeometryFromText('" + wkt + "'))", INTEGER, expected);
    }

    @Test
    public void testSTUnion()
    {
        List<String> emptyWkts =
                ImmutableList.of(
                        "POINT EMPTY",
                        "MULTIPOINT EMPTY",
                        "LINESTRING EMPTY",
                        "MULTILINESTRING EMPTY",
                        "POLYGON EMPTY",
                        "MULTIPOLYGON EMPTY",
                        "GEOMETRYCOLLECTION EMPTY");
        List<String> simpleWkts =
                ImmutableList.of(
                        "POINT (1 2)",
                        "MULTIPOINT ((1 2), (3 4))",
                        "LINESTRING (0 0, 2 2, 4 4)",
                        "MULTILINESTRING ((0 0, 2 2, 4 4), (5 5, 7 7, 9 9))",
                        "POLYGON ((0 0, 0 1, 1 1, 1 0, 0 0))",
                        "MULTIPOLYGON (((1 1, 1 3, 3 3, 3 1, 1 1)), ((2 4, 2 6, 6 6, 6 4, 2 4)))",
                        "GEOMETRYCOLLECTION (LINESTRING (0 5, 5 5), POLYGON ((1 1, 1 3, 3 3, 3 1, 1 1)))");

        // empty geometry
        for (String emptyWkt : emptyWkts) {
            for (String simpleWkt : simpleWkts) {
                assertUnion(emptyWkt, simpleWkt, simpleWkt);
            }
        }

        // self union
        for (String simpleWkt : simpleWkts) {
            assertUnion(simpleWkt, simpleWkt, simpleWkt);
        }

        // touching union
        assertUnion("POINT (1 2)", "MULTIPOINT ((1 2), (3 4))", "MULTIPOINT ((1 2), (3 4))");
        assertUnion("MULTIPOINT ((1 2))", "MULTIPOINT ((1 2), (3 4))", "MULTIPOINT ((1 2), (3 4))");
        assertUnion("LINESTRING (0 1, 1 2)", "LINESTRING (1 2, 3 4)", "LINESTRING (0 1, 1 2, 3 4)");
        assertUnion("MULTILINESTRING ((0 0, 2 2, 4 4), (5 5, 7 7, 9 9))", "MULTILINESTRING ((5 5, 7 7, 9 9), (11 11, 13 13, 15 15))", "MULTILINESTRING ((0 0, 2 2, 4 4), (5 5, 7 7, 9 9), (11 11, 13 13, 15 15))");
        assertUnion("POLYGON ((0 0, 0 1, 1 1, 1 0, 0 0))", "POLYGON ((1 0, 2 0, 2 1, 1 1, 1 0))", "POLYGON ((0 0, 0 1, 1 1, 2 1, 2 0, 1 0, 0 0))");
        assertUnion("MULTIPOLYGON (((0 0, 0 1, 1 1, 1 0, 0 0)))", "MULTIPOLYGON (((1 0, 2 0, 2 1, 1 1, 1 0)))", "POLYGON ((0 0, 0 1, 1 1, 2 1, 2 0, 1 0, 0 0))");
        assertUnion("GEOMETRYCOLLECTION (POLYGON ((0 0, 0 1, 1 1, 1 0, 0 0)), POINT (1 2))", "GEOMETRYCOLLECTION (POLYGON ((1 0, 2 0, 2 1, 1 1, 1 0)), MULTIPOINT ((1 2), (3 4)))", "GEOMETRYCOLLECTION (MULTIPOINT ((1 2), (3 4)), POLYGON ((0 0, 0 1, 1 1, 2 1, 2 0, 1 0, 0 0)))");

        // within union
        assertUnion("MULTIPOINT ((20 20), (25 25))", "POINT (25 25)", "MULTIPOINT ((20 20), (25 25))");
        assertUnion("LINESTRING (20 20, 30 30)", "POINT (25 25)", "LINESTRING (20 20, 25 25, 30 30)");
        assertUnion("LINESTRING (20 20, 30 30)", "LINESTRING (25 25, 27 27)", "LINESTRING (20 20, 25 25, 27 27, 30 30)");
        assertUnion("POLYGON ((0 0, 0 4, 4 4, 4 0, 0 0))", "POLYGON ((1 1, 1 2, 2 2, 2 1, 1 1))", "POLYGON ((0 0, 0 4, 4 4, 4 0, 0 0))");
        assertUnion("MULTIPOLYGON (((0 0, 0 2, 2 2, 2 0, 0 0)), ((2 2, 2 4, 4 4, 4 2, 2 2)))", "POLYGON ((2 2, 2 3, 3 3, 3 2, 2 2))", "MULTIPOLYGON (((2 2, 2 3, 2 4, 4 4, 4 2, 3 2, 2 2)), ((0 0, 0 2, 2 2, 2 0, 0 0)))");
        assertUnion("GEOMETRYCOLLECTION (POLYGON ((0 0, 0 4, 4 4, 4 0, 0 0)), MULTIPOINT ((20 20), (25 25)))", "GEOMETRYCOLLECTION (POLYGON ((1 1, 1 2, 2 2, 2 1, 1 1)), POINT (25 25))", "GEOMETRYCOLLECTION (MULTIPOINT ((20 20), (25 25)), POLYGON ((0 0, 0 4, 4 4, 4 0, 0 0)))");

        // overlap union
        assertUnion("LINESTRING (1 1, 3 1)", "LINESTRING (2 1, 4 1)", "LINESTRING (1 1, 2 1, 3 1, 4 1)");
        assertUnion("MULTILINESTRING ((1 1, 3 1))", "MULTILINESTRING ((2 1, 4 1))", "LINESTRING (1 1, 2 1, 3 1, 4 1)");
        assertUnion("POLYGON ((1 1, 3 1, 3 3, 1 3, 1 1))", "POLYGON ((2 2, 4 2, 4 4, 2 4, 2 2))", "POLYGON ((1 1, 1 3, 2 3, 2 4, 4 4, 4 2, 3 2, 3 1, 1 1))");
        assertUnion("MULTIPOLYGON (((1 1, 3 1, 3 3, 1 3, 1 1)))", "MULTIPOLYGON (((2 2, 4 2, 4 4, 2 4, 2 2)))", "POLYGON ((1 1, 1 3, 2 3, 2 4, 4 4, 4 2, 3 2, 3 1, 1 1))");
        assertUnion("GEOMETRYCOLLECTION (POLYGON ((1 1, 3 1, 3 3, 1 3, 1 1)), LINESTRING (1 1, 3 1))", "GEOMETRYCOLLECTION (POLYGON ((2 2, 4 2, 4 4, 2 4, 2 2)), LINESTRING (2 1, 4 1))", "GEOMETRYCOLLECTION (LINESTRING (3 1, 4 1), POLYGON ((1 1, 1 3, 2 3, 2 4, 4 4, 4 2, 3 2, 3 1, 2 1, 1 1)))");
    }

    private void assertUnion(String leftWkt, String rightWkt, String expectWkt)
    {
        assertFunction(format("ST_ASText(ST_Union(ST_GeometryFromText('%s'), ST_GeometryFromText('%s')))", leftWkt, rightWkt), VARCHAR, expectWkt);
        assertFunction(format("ST_ASText(ST_Union(ST_GeometryFromText('%s'), ST_GeometryFromText('%s')))", rightWkt, leftWkt), VARCHAR, expectWkt);
    }

    private void assertInvalidGeometryCollectionUnion(String validWkt)
    {
        assertInvalidFunction(format("ST_Union(ST_GeometryFromText('%s'), ST_GeometryFromText('GEOMETRYCOLLECTION (POINT(2 3))'))", validWkt), "ST_Union only applies to POINT or MULTI_POINT or LINE_STRING or MULTI_LINE_STRING or POLYGON or MULTI_POLYGON. Input type is: GEOMETRY_COLLECTION");
        assertInvalidFunction(format("ST_Union(ST_GeometryFromText('GEOMETRYCOLLECTION (POINT(2 3))'), ST_GeometryFromText('%s'))", validWkt), "ST_Union only applies to POINT or MULTI_POINT or LINE_STRING or MULTI_LINE_STRING or POLYGON or MULTI_POLYGON. Input type is: GEOMETRY_COLLECTION");
    }

    @Test
    public void testSTGeometryN()
    {
        assertSTGeometryN("POINT EMPTY", 1, null);
        assertSTGeometryN("LINESTRING EMPTY", 1, null);
        assertSTGeometryN("POLYGON EMPTY", 1, null);
        assertSTGeometryN("MULTIPOINT EMPTY", 1, null);
        assertSTGeometryN("MULTILINESTRING EMPTY", 1, null);
        assertSTGeometryN("MULTIPOLYGON EMPTY", 1, null);
        assertSTGeometryN("POINT EMPTY", 0, null);
        assertSTGeometryN("LINESTRING EMPTY", 0, null);
        assertSTGeometryN("POLYGON EMPTY", 0, null);
        assertSTGeometryN("MULTIPOINT EMPTY", 0, null);
        assertSTGeometryN("MULTILINESTRING EMPTY", 0, null);
        assertSTGeometryN("MULTIPOLYGON EMPTY", 0, null);
        assertSTGeometryN("POINT (1 2)", 1, "POINT (1 2)");
        assertSTGeometryN("POINT (1 2)", -1, null);
        assertSTGeometryN("POINT (1 2)", 2, null);
        assertSTGeometryN("LINESTRING(77.29 29.07, 77.42 29.26, 77.27 29.31, 77.29 29.07)", 1, "LINESTRING (77.29 29.07, 77.42 29.26, 77.27 29.31, 77.29 29.07)");
        assertSTGeometryN("LINESTRING(77.29 29.07, 77.42 29.26, 77.27 29.31, 77.29 29.07)", 2, null);
        assertSTGeometryN("LINESTRING(77.29 29.07, 77.42 29.26, 77.27 29.31, 77.29 29.07)", -1, null);
        assertSTGeometryN("POLYGON ((0 0, 0 1, 1 1, 1 0, 0 0))", 1, "POLYGON ((0 0, 0 1, 1 1, 1 0, 0 0))");
        assertSTGeometryN("POLYGON ((0 0, 0 1, 1 1, 1 0, 0 0))", 2, null);
        assertSTGeometryN("POLYGON ((0 0, 0 1, 1 1, 1 0, 0 0))", -1, null);
        assertSTGeometryN("MULTIPOINT (1 2, 2 4, 3 6, 4 8)", 1, "POINT (1 2)");
        assertSTGeometryN("MULTIPOINT (1 2, 2 4, 3 6, 4 8)", 2, "POINT (2 4)");
        assertSTGeometryN("MULTIPOINT (1 2, 2 4, 3 6, 4 8)", 0, null);
        assertSTGeometryN("MULTIPOINT (1 2, 2 4, 3 6, 4 8)", 5, null);
        assertSTGeometryN("MULTIPOINT (1 2, 2 4, 3 6, 4 8)", -1, null);
        assertSTGeometryN("MULTILINESTRING ((1 1, 5 1), (2 4, 4 4))", 1, "LINESTRING (1 1, 5 1)");
        assertSTGeometryN("MULTILINESTRING ((1 1, 5 1), (2 4, 4 4))", 2, "LINESTRING (2 4, 4 4)");
        assertSTGeometryN("MULTILINESTRING ((1 1, 5 1), (2 4, 4 4))", 0, null);
        assertSTGeometryN("MULTILINESTRING ((1 1, 5 1), (2 4, 4 4))", 3, null);
        assertSTGeometryN("MULTILINESTRING ((1 1, 5 1), (2 4, 4 4))", -1, null);
        assertSTGeometryN("MULTIPOLYGON (((1 1, 1 3, 3 3, 3 1, 1 1)), ((2 4, 2 6, 6 6, 6 4, 2 4)))", 1, "POLYGON ((1 1, 1 3, 3 3, 3 1, 1 1))");
        assertSTGeometryN("MULTIPOLYGON (((1 1, 1 3, 3 3, 3 1, 1 1)), ((2 4, 2 6, 6 6, 6 4, 2 4)))", 2, "POLYGON ((2 4, 2 6, 6 6, 6 4, 2 4))");
        assertSTGeometryN("MULTIPOLYGON (((1 1, 1 3, 3 3, 3 1, 1 1)), ((2 4, 2 6, 6 6, 6 4, 2 4)))", 0, null);
        assertSTGeometryN("MULTIPOLYGON (((1 1, 1 3, 3 3, 3 1, 1 1)), ((2 4, 2 6, 6 6, 6 4, 2 4)))", 3, null);
        assertSTGeometryN("MULTIPOLYGON (((1 1, 1 3, 3 3, 3 1, 1 1)), ((2 4, 2 6, 6 6, 6 4, 2 4)))", -1, null);
        assertSTGeometryN("GEOMETRYCOLLECTION(POINT(2 3), LINESTRING (2 3, 3 4))", 1, "POINT (2 3)");
        assertSTGeometryN("GEOMETRYCOLLECTION(POINT(2 3), LINESTRING (2 3, 3 4))", 2, "LINESTRING (2 3, 3 4)");
        assertSTGeometryN("GEOMETRYCOLLECTION(POINT(2 3), LINESTRING (2 3, 3 4))", 3, null);
        assertSTGeometryN("GEOMETRYCOLLECTION(POINT(2 3), LINESTRING (2 3, 3 4))", 0, null);
        assertSTGeometryN("GEOMETRYCOLLECTION(POINT(2 3), LINESTRING (2 3, 3 4))", -1, null);
    }

    private void assertSTGeometryN(String wkt, int index, String expected)
    {
        assertFunction("ST_ASText(ST_GeometryN(ST_GeometryFromText('" + wkt + "')," + index + "))", VARCHAR, expected);
    }

    @Test
    public void testSTLineString()
    {
        // General case, 2+ points
        assertFunction("ST_LineString(array[ST_Point(1,2), ST_Point(3,4)])", GEOMETRY, "LINESTRING (1 2, 3 4)");
        assertFunction("ST_LineString(array[ST_Point(1,2), ST_Point(3,4), ST_Point(5, 6)])", GEOMETRY, "LINESTRING (1 2, 3 4, 5 6)");
        assertFunction("ST_LineString(array[ST_Point(1,2), ST_Point(3,4), ST_Point(5,6), ST_Point(7,8)])", GEOMETRY, "LINESTRING (1 2, 3 4, 5 6, 7 8)");

        // Other ways of creating points
        assertFunction("ST_LineString(array[ST_GeometryFromText('POINT (1 2)'), ST_GeometryFromText('POINT (3 4)')])", GEOMETRY, "LINESTRING (1 2, 3 4)");

        // Duplicate consecutive points throws exception
        assertInvalidFunction("ST_LineString(array[ST_Point(1, 2), ST_Point(1, 2)])", INVALID_FUNCTION_ARGUMENT, "Invalid input to ST_LineString: consecutive duplicate points at index 2");
        assertFunction("ST_LineString(array[ST_Point(1, 2), ST_Point(3, 4), ST_Point(1, 2)])", GEOMETRY, "LINESTRING (1 2, 3 4, 1 2)");

        // Single point
        assertFunction("ST_LineString(array[ST_Point(9,10)])", GEOMETRY, "LINESTRING EMPTY");

        // Zero points
        assertFunction("ST_LineString(array[])", GEOMETRY, "LINESTRING EMPTY");

        // Only points can be passed
        assertInvalidFunction("ST_LineString(array[ST_Point(7,8), ST_GeometryFromText('LINESTRING (1 2, 3 4)')])", INVALID_FUNCTION_ARGUMENT, "Invalid input to ST_LineString: geometry is not a point: LINE_STRING at index 2");

        // Nulls points are invalid
        assertInvalidFunction("ST_LineString(array[NULL])", INVALID_FUNCTION_ARGUMENT, "Invalid input to ST_LineString: null at index 1");
        assertInvalidFunction("ST_LineString(array[ST_Point(1,2), NULL])", INVALID_FUNCTION_ARGUMENT, "Invalid input to ST_LineString: null at index 2");
        assertInvalidFunction("ST_LineString(array[ST_Point(1, 2), NULL, ST_Point(3, 4)])", INVALID_FUNCTION_ARGUMENT, "Invalid input to ST_LineString: null at index 2");
        assertInvalidFunction("ST_LineString(array[ST_Point(1, 2), NULL, ST_Point(3, 4), NULL])", INVALID_FUNCTION_ARGUMENT, "Invalid input to ST_LineString: null at index 2");

        // Empty points are invalid
        assertInvalidFunction("ST_LineString(array[ST_GeometryFromText('POINT EMPTY')])", INVALID_FUNCTION_ARGUMENT, "Invalid input to ST_LineString: empty point at index 1");
        assertInvalidFunction("ST_LineString(array[ST_Point(1,2), ST_GeometryFromText('POINT EMPTY')])", INVALID_FUNCTION_ARGUMENT, "Invalid input to ST_LineString: empty point at index 2");
        assertInvalidFunction("ST_LineString(array[ST_Point(1,2), ST_GeometryFromText('POINT EMPTY'), ST_Point(3,4)])", INVALID_FUNCTION_ARGUMENT, "Invalid input to ST_LineString: empty point at index 2");
        assertInvalidFunction("ST_LineString(array[ST_Point(1,2), ST_GeometryFromText('POINT EMPTY'), ST_Point(3,4), ST_GeometryFromText('POINT EMPTY')])", INVALID_FUNCTION_ARGUMENT, "Invalid input to ST_LineString: empty point at index 2");
    }

    @Test
    public void testMultiPoint()
    {
        // General case, 2+ points
        assertMultiPoint("MULTIPOINT ((1 2), (3 4))", "POINT (1 2)", "POINT (3 4)");
        assertMultiPoint("MULTIPOINT ((1 2), (3 4), (5 6))", "POINT (1 2)", "POINT (3 4)", "POINT (5 6)");
        assertMultiPoint("MULTIPOINT ((1 2), (3 4), (5 6), (7 8))", "POINT (1 2)", "POINT (3 4)", "POINT (5 6)", "POINT (7 8)");

        // Duplicate points work
        assertMultiPoint("MULTIPOINT ((1 2), (1 2))", "POINT (1 2)", "POINT (1 2)");
        assertMultiPoint("MULTIPOINT ((1 2), (3 4), (1 2))", "POINT (1 2)", "POINT (3 4)", "POINT (1 2)");

        // Single point
        assertMultiPoint("MULTIPOINT ((1 2))", "POINT (1 2)");

        // Empty array
        assertFunction("ST_MultiPoint(array[])", GEOMETRY, null);

        // Only points can be passed
        assertInvalidMultiPoint("geometry is not a point: LINE_STRING at index 2", "POINT (7 8)", "LINESTRING (1 2, 3 4)");

        // Null point raises exception
        assertInvalidFunction("ST_MultiPoint(array[null])", "Invalid input to ST_MultiPoint: null at index 1");
        assertInvalidMultiPoint("null at index 3", "POINT (1 2)", "POINT (1 2)", null);
        assertInvalidMultiPoint("null at index 2", "POINT (1 2)", null, "POINT (3 4)");
        assertInvalidMultiPoint("null at index 2", "POINT (1 2)", null, "POINT (3 4)", null);

        // Empty point raises exception
        assertInvalidMultiPoint("empty point at index 1", "POINT EMPTY");
        assertInvalidMultiPoint("empty point at index 2", "POINT (1 2)", "POINT EMPTY");
    }

    private void assertMultiPoint(String expectedWkt, String... pointWkts)
    {
        assertFunction(
                format(
                        "ST_MultiPoint(array[%s])",
                        Arrays.stream(pointWkts)
                            .map(wkt -> wkt == null ? "null" : format("ST_GeometryFromText('%s')", wkt))
                            .collect(Collectors.joining(","))),
                GEOMETRY,
                expectedWkt);
    }

    private void assertInvalidMultiPoint(String errorMessage, String... pointWkts)
    {
        assertInvalidFunction(
                format(
                        "ST_MultiPoint(array[%s])",
                        Arrays.stream(pointWkts)
                            .map(wkt -> wkt == null ? "null" : format("ST_GeometryFromText('%s')", wkt))
                            .collect(Collectors.joining(","))),
                INVALID_FUNCTION_ARGUMENT,
                format("Invalid input to ST_MultiPoint: %s", errorMessage));
    }

    @Test
    public void testSTPointN()
    {
        assertPointN("LINESTRING(1 2, 3 4, 5 6, 7 8)", 1, "POINT (1 2)");
        assertPointN("LINESTRING(1 2, 3 4, 5 6, 7 8)", 3, "POINT (5 6)");
        assertPointN("LINESTRING(1 2, 3 4, 5 6, 7 8)", 10, null);
        assertPointN("LINESTRING(1 2, 3 4, 5 6, 7 8)", 0, null);
        assertPointN("LINESTRING(1 2, 3 4, 5 6, 7 8)", -1, null);

        assertInvalidPointN("POINT (1 2)", "POINT");
        assertInvalidPointN("MULTIPOINT (1 1, 2 2)", "MULTI_POINT");
        assertInvalidPointN("MULTILINESTRING ((1 1, 2 2), (3 3, 4 4))", "MULTI_LINE_STRING");
        assertInvalidPointN("POLYGON ((0 0, 0 1, 1 1, 1 0, 0 0))", "POLYGON");
        assertInvalidPointN("MULTIPOLYGON (((1 1, 1 4, 4 4, 4 1, 1 1)), ((1 1, 1 4, 4 4, 4 1, 1 1)))", "MULTI_POLYGON");
        assertInvalidPointN("GEOMETRYCOLLECTION(POINT(4 6),LINESTRING(4 6, 7 10))", "GEOMETRY_COLLECTION");
    }

    private void assertPointN(String wkt, int index, String expected)
    {
        assertFunction(format("ST_ASText(ST_PointN(ST_GeometryFromText('%s'), %d))", wkt, index), VARCHAR, expected);
    }

    private void assertInvalidPointN(String wkt, String type)
    {
        String message = format("ST_PointN only applies to LINE_STRING. Input type is: %s", type);
        assertInvalidFunction(format("ST_PointN(ST_GeometryFromText('%s'), 1)", wkt), message);
    }

    @Test
    public void testSTGeometries()
    {
        assertFunction("ST_Geometries(ST_GeometryFromText('POINT EMPTY'))", new ArrayType(GEOMETRY), null);
        assertSTGeometries("POINT (1 5)", "POINT (1 5)");
        assertSTGeometries("LINESTRING (77.29 29.07, 77.42 29.26, 77.27 29.31, 77.29 29.07)", "LINESTRING (77.29 29.07, 77.42 29.26, 77.27 29.31, 77.29 29.07)");
        assertSTGeometries("POLYGON ((0 0, 0 1, 1 1, 1 0, 0 0))", "POLYGON ((0 0, 0 1, 1 1, 1 0, 0 0))");
        assertSTGeometries("MULTIPOINT (1 2, 4 8, 16 32)", "POINT (1 2)", "POINT (4 8)", "POINT (16 32)");
        assertSTGeometries("MULTILINESTRING ((1 1, 2 2))", "LINESTRING (1 1, 2 2)");
        assertSTGeometries("MULTIPOLYGON (((0 0, 0 1, 1 1, 1 0, 0 0)), ((1 1, 3 1, 3 3, 1 3, 1 1)))",
                "POLYGON ((0 0, 0 1, 1 1, 1 0, 0 0))", "POLYGON ((1 1, 1 3, 3 3, 3 1, 1 1))");
        assertSTGeometries("GEOMETRYCOLLECTION (POINT (2 3), LINESTRING (2 3, 3 4))", "POINT (2 3)", "LINESTRING (2 3, 3 4)");
        assertSTGeometries("GEOMETRYCOLLECTION(MULTIPOINT(0 0, 1 1), GEOMETRYCOLLECTION(MULTILINESTRING((2 2, 3 3))))",
                "MULTIPOINT ((0 0), (1 1))", "GEOMETRYCOLLECTION (MULTILINESTRING ((2 2, 3 3)))");
    }

    private void assertSTGeometries(String wkt, String... expected)
    {
        assertFunction(String.format("transform(ST_Geometries(ST_GeometryFromText('%s')), x -> ST_ASText(x))", wkt), new ArrayType(VARCHAR), ImmutableList.copyOf(expected));
    }

    @Test
    public void testSTInteriorRingN()
    {
        assertInvalidInteriorRingN("POINT EMPTY", 0, "POINT");
        assertInvalidInteriorRingN("LINESTRING (1 2, 2 3, 3 4)", 1, "LINE_STRING");
        assertInvalidInteriorRingN("MULTIPOINT (1 1, 2 3, 5 8)", -1, "MULTI_POINT");
        assertInvalidInteriorRingN("MULTILINESTRING ((2 4, 4 2), (3 5, 5 3))", 0, "MULTI_LINE_STRING");
        assertInvalidInteriorRingN("MULTIPOLYGON (((1 1, 1 3, 3 3, 3 1, 1 1)), ((2 4, 2 6, 6 6, 6 4, 2 4)))", 2, "MULTI_POLYGON");
        assertInvalidInteriorRingN("GEOMETRYCOLLECTION (POINT (2 2), POINT (10 20))", 1, "GEOMETRY_COLLECTION");

        assertInteriorRingN("POLYGON ((0 0, 0 1, 1 1, 1 0, 0 0))", 1, null);
        assertInteriorRingN("POLYGON ((0 0, 0 1, 1 1, 1 0, 0 0))", 2, null);
        assertInteriorRingN("POLYGON ((0 0, 0 1, 1 1, 1 0, 0 0))", -1, null);
        assertInteriorRingN("POLYGON ((0 0, 0 1, 1 1, 1 0, 0 0))", 0, null);
        assertInteriorRingN("POLYGON ((0 0, 0 3, 3 3, 3 0, 0 0), (1 1, 2 1, 2 2, 1 2, 1 1))", 1, "LINESTRING (1 1, 2 1, 2 2, 1 2, 1 1)");
        assertInteriorRingN("POLYGON ((0 0, 0 5, 5 5, 5 0, 0 0), (1 1, 2 1, 2 2, 1 2, 1 1), (3 3, 4 3, 4 4, 3 4, 3 3))", 2, "LINESTRING (3 3, 4 3, 4 4, 3 4, 3 3)");
    }

    private void assertInteriorRingN(String wkt, int index, String expected)
    {
        assertFunction(format("ST_ASText(ST_InteriorRingN(ST_GeometryFromText('%s'), %d))", wkt, index), VARCHAR, expected);
    }

    private void assertInvalidInteriorRingN(String wkt, int index, String geometryType)
    {
        assertInvalidFunction(format("ST_InteriorRingN(ST_GeometryFromText('%s'), %d)", wkt, index), format("ST_InteriorRingN only applies to POLYGON. Input type is: %s", geometryType));
    }

    @Test
    public void testSTGeometryType()
    {
        assertFunction("ST_GeometryType(ST_Point(1, 4))", VARCHAR, "ST_Point");
        assertFunction("ST_GeometryType(ST_GeometryFromText('LINESTRING (1 1, 2 2)'))", VARCHAR, "ST_LineString");
        assertFunction("ST_GeometryType(ST_GeometryFromText('POLYGON ((1 1, 1 4, 4 4, 4 1, 1 1))'))", VARCHAR, "ST_Polygon");
        assertFunction("ST_GeometryType(ST_GeometryFromText('MULTIPOINT (1 1, 2 2)'))", VARCHAR, "ST_MultiPoint");
        assertFunction("ST_GeometryType(ST_GeometryFromText('MULTILINESTRING ((1 1, 2 2), (3 3, 4 4))'))", VARCHAR, "ST_MultiLineString");
        assertFunction("ST_GeometryType(ST_GeometryFromText('MULTIPOLYGON (((1 1, 1 4, 4 4, 4 1, 1 1)), ((1 1, 1 4, 4 4, 4 1, 1 1)))'))", VARCHAR, "ST_MultiPolygon");
        assertFunction("ST_GeometryType(ST_GeometryFromText('GEOMETRYCOLLECTION(POINT(4 6),LINESTRING(4 6, 7 10))'))", VARCHAR, "ST_GeomCollection");
        assertFunction("ST_GeometryType(ST_Envelope(ST_GeometryFromText('LINESTRING (1 1, 2 2)')))", VARCHAR, "ST_Polygon");
    }

    @Test
    public void testFlattenGeometryCollections()
    {
        assertFlattenGeometryCollections("POINT (0 0)", "POINT (0 0)");
        assertFlattenGeometryCollections("MULTIPOINT ((0 0), (1 1))", "MULTIPOINT ((0 0), (1 1))");
        assertFlattenGeometryCollections("GEOMETRYCOLLECTION EMPTY");
        assertFlattenGeometryCollections("GEOMETRYCOLLECTION (POINT EMPTY)", "POINT EMPTY");
        assertFlattenGeometryCollections("GEOMETRYCOLLECTION (MULTIPOLYGON EMPTY)", "MULTIPOLYGON EMPTY");
        assertFlattenGeometryCollections("GEOMETRYCOLLECTION (POINT (0 0))", "POINT (0 0)");
        assertFlattenGeometryCollections(
                "GEOMETRYCOLLECTION (POINT (0 0), GEOMETRYCOLLECTION (POINT (1 1)))",
                "POINT (1 1)", "POINT (0 0)");
    }

    private void assertFlattenGeometryCollections(String wkt, String... expected)
    {
        assertFunction(
                String.format("transform(flatten_geometry_collections(ST_GeometryFromText('%s')), x -> ST_ASText(x))", wkt),
                new ArrayType(VARCHAR), ImmutableList.copyOf(expected));
    }

    @Test
    public void testSTGeometryFromText()
    {
        assertInvalidFunction("ST_GeometryFromText('xyz')", "Invalid WKT: Unknown geometry type: XYZ (line 1)");
        assertInvalidFunction("ST_GeometryFromText('LINESTRING (-71.3839245 42.3128124)')", "Invalid WKT: Invalid number of points in LineString (found 1 - must be 0 or >= 2)");
        assertInvalidFunction("ST_GeometryFromText('POLYGON ((-13.719076 9.508430, -13.723493 9.510049, -13.719076 9.508430))')", "Invalid WKT: Invalid number of points in LinearRing (found 3 - must be 0 or >= 4)");
        assertInvalidFunction("ST_GeometryFromText('POLYGON ((-13.637339 9.617113, -13.637339 9.617113))')", "Invalid WKT: Invalid number of points in LinearRing (found 2 - must be 0 or >= 4)");
        assertInvalidFunction("ST_GeometryFromText('POLYGON(0 0)')", INVALID_FUNCTION_ARGUMENT, "Invalid WKT: Expected EMPTY or ( but found '0' (line 1)");
        assertInvalidFunction("ST_GeometryFromText('POLYGON((0 0))')", INVALID_FUNCTION_ARGUMENT, "Invalid WKT: Invalid number of points in LineString (found 1 - must be 0 or >= 2)");
    }

    @Test
    public void testSTGeometryFromBinary()
    {
        assertFunction("ST_GeomFromBinary(null)", GEOMETRY, null);

        // empty geometries
        assertGeomFromBinary("POINT EMPTY");
        assertGeomFromBinary("MULTIPOINT EMPTY");
        assertGeomFromBinary("LINESTRING EMPTY");
        assertGeomFromBinary("MULTILINESTRING EMPTY");
        assertGeomFromBinary("POLYGON EMPTY");
        assertGeomFromBinary("MULTIPOLYGON EMPTY");
        assertGeomFromBinary("GEOMETRYCOLLECTION EMPTY");

        // valid nonempty geometries
        assertGeomFromBinary("POINT (1 2)");
        assertGeomFromBinary("MULTIPOINT ((1 2), (3 4))");
        assertGeomFromBinary("LINESTRING (0 0, 1 2, 3 4)");
        assertGeomFromBinary("MULTILINESTRING ((1 1, 5 1), (2 4, 4 4))");
        assertGeomFromBinary("POLYGON ((0 0, 0 1, 1 1, 1 0, 0 0))");
        assertGeomFromBinary("POLYGON ((0 0, 0 3, 3 3, 3 0, 0 0), (1 1, 2 1, 2 2, 1 2, 1 1))");
        assertGeomFromBinary("MULTIPOLYGON (((1 1, 1 3, 3 3, 3 1, 1 1)), ((2 4, 2 6, 6 6, 6 4, 2 4)))");
        assertGeomFromBinary("GEOMETRYCOLLECTION (POINT (1 2), LINESTRING (0 0, 1 2, 3 4), POLYGON ((0 0, 0 1, 1 1, 1 0, 0 0)))");

        // array of geometries
        assertFunction("transform(array[ST_AsBinary(ST_Point(1, 2)), ST_AsBinary(ST_Point(3, 4))], wkb -> ST_AsText(ST_GeomFromBinary(wkb)))", new ArrayType(VARCHAR), ImmutableList.of("POINT (1 2)", "POINT (3 4)"));

        // invalid geometries
        assertGeomFromBinary("MULTIPOINT ((0 0), (0 1), (1 1), (0 1))");
        assertGeomFromBinary("LINESTRING (0 0, 0 1, 0 1, 1 1, 1 0, 0 0)");

        // invalid binary
        assertInvalidFunction("ST_GeomFromBinary(from_hex('deadbeef'))", "Invalid WKB");
        assertInvalidFunction("ST_AsBinary(ST_GeometryFromText('POLYGON ((0 0, 1 1, 0 1, 1 0, 0 0))'))", INVALID_FUNCTION_ARGUMENT, "Invalid geometry: corrupted geometry");
    }

    private void assertGeomFromBinary(String wkt)
    {
        assertFunction(format("ST_AsText(ST_GeomFromBinary(ST_AsBinary(ST_GeometryFromText('%s'))))", wkt), VARCHAR, wkt);
    }
}
