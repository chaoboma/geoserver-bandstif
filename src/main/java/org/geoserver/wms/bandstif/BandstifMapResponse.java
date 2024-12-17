/* (c) 2014-2015 Open Source Geospatial Foundation - all rights reserved
 * (c) 2001 - 2013 OpenPlans
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.wms.bandstif;

import com.sun.media.imageioimpl.plugins.raw.RawImageWriterSpi;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.RenderedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;
import javax.media.jai.Interpolation;
import javax.media.jai.JAI;
import org.geoserver.data.util.CoverageUtils;
import org.geoserver.platform.ServiceException;
import org.geoserver.wms.GetMapRequest;
import org.geoserver.wms.MapLayerInfo;
import org.geoserver.wms.MapProducerCapabilities;
import org.geoserver.wms.WMS;
import org.geoserver.wms.WMSMapContent;
import org.geoserver.wms.bandstif.util.BandstifWCSUtils;
import org.geoserver.wms.bandstif.util.RecodeRaster;
import org.geoserver.wms.map.RenderedImageMapResponse;
import org.geotools.coverage.CoverageFactoryFinder;
import org.geotools.coverage.grid.GeneralGridEnvelope;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.coverage.grid.io.GridCoverage2DReader;
import org.geotools.coverage.processing.CoverageProcessor;
import org.geotools.gce.geotiff.GeoTiffFormat;
import org.geotools.gce.geotiff.GeoTiffWriteParams;
import org.geotools.gce.geotiff.GeoTiffWriter;
import org.geotools.geometry.GeneralEnvelope;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.image.io.ImageIOExt;
import org.geotools.referencing.CRS;
import org.geotools.referencing.operation.transform.AffineTransform2D;
import org.geotools.util.logging.Logging;
import org.locationtech.jts.geom.Envelope;
import org.opengis.coverage.grid.GridGeometry;
import org.opengis.parameter.GeneralParameterValue;
import org.opengis.parameter.ParameterValueGroup;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.datum.PixelInCell;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;
import org.vfny.geoserver.wcs.WcsException;

/**
 * Map producer for producing Raw bil images out of an elevation model. Modeled after the
 * GeoTIFFMapResponse, relying on Geotools and the RawImageWriterSpi
 *
 * @author Tishampati Dhar
 * @since 2.0.x
 */
public final class BandstifMapResponse extends RenderedImageMapResponse {
    /** A logger for this class. */
    private static final Logger LOGGER = Logging.getLogger(BandstifMapResponse.class);

    /** the only MIME type this map producer supports */
    static final String MIME_TYPE = "image/bil";

    private static final String[] OUTPUT_FORMATS = {
        MIME_TYPE,
        "application/bandstif",
        "application/bandstifbyte8",
        "application/bandstiffloat32"
    };

    /** GridCoverageFactory. - Where do we use this again ? */
    private static final GridCoverageFactory factory =
            CoverageFactoryFinder.getGridCoverageFactory(null);

    /** Raw Image Writer * */
    private static final ImageWriterSpi writerSPI = new RawImageWriterSpi();

    /**
     * Constructor for a {@link BandstifMapResponse}.
     *
     * @param wms that is asking us to encode the image.
     */
    public BandstifMapResponse(final WMS wms) {
        super(OUTPUT_FORMATS, wms);
    }

    @Override
    public void formatImageOutputStream(
            RenderedImage image, OutputStream outStream, WMSMapContent mapContent)
            throws ServiceException, IOException {
        // TODO: Write reprojected terrain tile
        // TODO Get request tile size
        long start = new Date().getTime();
        final GetMapRequest request = mapContent.getRequest();
        // String bandstifEncoding = (String) request.getFormat();
        String srs = (String) request.getSRS();
        int height = request.getHeight();
        int width = request.getWidth();
        Envelope requestBbox = request.getBbox();
        List<MapLayerInfo> reqlayers = request.getLayers();

        // Can't fetch bil for more than 1 layer
        if (reqlayers.size() > 1) {
            throw new ServiceException("Cannot combine layers into BIL output");
        }

        // Get BIL layer configuration. This configuration is set by the server administrator
        // using the BIL layer config panel.
        MapLayerInfo mapLayerInfo = reqlayers.get(0);
        try {

            LOGGER.info("reqlayers.get(0).getBoundingBox():" + reqlayers.get(0).getBoundingBox());

            LOGGER.info("reqlayers.get(0).getName():" + reqlayers.get(0).getName());
        } catch (Exception e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }
        // MetadataMap metadata = mapLayerInfo.getResource().getMetadata();
        // LOGGER.info("metadata.toString():" + metadata.toString());
        // String defaultDataType = (String) metadata.get(BandstifConfig.DEFAULT_DATA_TYPE);
        // LOGGER.info("defaultDataType:" + defaultDataType);

        GridCoverage2DReader coverageReader =
                (GridCoverage2DReader) mapLayerInfo.getCoverageReader();
        GeneralEnvelope destinationEnvelope = new GeneralEnvelope(mapContent.getRenderingArea());

        /*
         * Try to use a gridcoverage style render
         */
        GridCoverage2D subCov = null;
        CoordinateReferenceSystem cvCRS = null;
        try {
            cvCRS =
                    ((GeneralEnvelope) coverageReader.getOriginalEnvelope())
                            .getCoordinateReferenceSystem();

            // this is the destination envelope in the coverage crs
            final GeneralEnvelope destinationEnvelopeInSourceCRS =
                    CRS.transform(destinationEnvelope, cvCRS);
            Rectangle destinationSize = null;
            destinationSize = new Rectangle(0, 0, request.getWidth(), request.getHeight());

            /** Checking for supported Interpolation Methods */
            // Interpolation interpolation =
            // Interpolation.getInstance(Interpolation.INTERP_BILINEAR);

            // /////////////////////////////////////////////////////////
            //
            // Reading the coverage
            //
            // /////////////////////////////////////////////////////////
            Map<Object, Object> parameters = new HashMap<Object, Object>();

            parameters.put(
                    AbstractGridFormat.READ_GRIDGEOMETRY2D.getName().toString(),
                    new GridGeometry2D(
                            new GeneralGridEnvelope(destinationSize),
                            destinationEnvelopeInSourceCRS));

            subCov =
                    coverageReader.read(
                            CoverageUtils.getParameters(
                                    coverageReader.getFormat().getReadParameters(),
                                    parameters,
                                    true));
        } catch (Exception e) {
            LOGGER.severe("Could not get a subcoverage");
        }

        // Object property = image.getProperty(NoDataContainer.GC_NODATA);

        CoverageProcessor processor = CoverageProcessor.getInstance();

        double minLong = requestBbox.getMinX();
        double maxLong = requestBbox.getMaxX();
        double minLat = requestBbox.getMinY();
        double maxLat = requestBbox.getMaxY();
        ReferencedEnvelope originalEnvelope = new ReferencedEnvelope(requestBbox, cvCRS);

        GridCoverageFactory gcf = new GridCoverageFactory();
        MathTransform gridToCRS = null;
        // MathTransform gridToCRS = reader.getOriginalGridToWorld(PixelInCell.CELL_CENTER);

        // System.out.println("gridToCRS:"+gridToCRS);

        // 计算目标分辨率
        RenderedImage renderedImage = subCov.getRenderedImage();

        int subCovWidth = renderedImage.getWidth();
        LOGGER.info("subCovWidth:" + subCovWidth);
        int subCovHight = renderedImage.getHeight();
        LOGGER.info("subCovHight:" + subCovHight);

        double resolutionX = 0;
        double resolutionY = 0;
        try {
            resolutionX = (maxLong - minLong) / width;
            resolutionY = (maxLat - minLat) / height;
        } catch (Exception e) {
            e.printStackTrace();
        }
        LOGGER.info("resolutionX:" + resolutionX);
        LOGGER.info("resolutionY:" + resolutionY);
        AffineTransform tx;
        // tx = new AffineTransform(0.00054 ,-0.00054, 0,31.71919290339184 ,117.28945910834541 , 0);
        tx = new AffineTransform(resolutionX, 0, 0, -resolutionY, 0, 0);
        gridToCRS = new AffineTransform2D(tx);
        System.out.println("gridToCRS:" + gridToCRS);

        final GeneralEnvelope intersectionEnvelope;
        // final GeneralEnvelope originalEnvelope = reader.getOriginalEnvelope();
        // final GeneralEnvelope requestedEnvelope;
        // final CoordinateReferenceSystem nativeCRS =
        // originalEnvelope.getCoordinateReferenceSystem();
        // final GeneralEnvelope requestedEnvelopeInNativeCRS;
        // requestedEnvelopeInNativeCRS = originalEnvelope.
        // requestedEnvelope = requestedEnvelopeInNativeCRS;
        final GeneralEnvelope intersectionEnvelopeInSourceCRS =
                new GeneralEnvelope(destinationEnvelope);
        intersectionEnvelopeInSourceCRS.intersect(originalEnvelope);
        intersectionEnvelope = intersectionEnvelopeInSourceCRS;
        System.out.println("intersectionEnvelope:" + intersectionEnvelope);
        final CoordinateReferenceSystem targetCRS;
        targetCRS = destinationEnvelope.getCoordinateReferenceSystem();
        Interpolation interpolation = Interpolation.getInstance(Interpolation.INTERP_NEAREST);
        final GridGeometry2D destinationGridGeometry =
                new GridGeometry2D(PixelInCell.CELL_CENTER, gridToCRS, intersectionEnvelope, null);
        System.out.println("4:" + new Date().getTime());
        ParameterValueGroup param = processor.getOperation("Resample").getParameters();

        System.out.println("destinationGridGeometry:" + destinationGridGeometry);
        param.parameter("Source").setValue(subCov);
        param.parameter("CoordinateReferenceSystem").setValue(targetCRS);
        param.parameter("GridGeometry").setValue(destinationGridGeometry);
        param.parameter("InterpolationType").setValue(interpolation);
        // System.out.println("param:"+param);
        /*GridGeometry2D targetGG =
                GridGeometry2D.wrap((GridGeometry) param.parameter("GridGeometry").getValue());
        MathTransform temp =
                GridGeometry2D.wrap((GridGeometry) param.parameter("GridGeometry").getValue())
                        .getCRSToGrid2D();
        System.out.println("targetGG:" + targetGG);
        System.out.println("temp:" + temp);
        */
        System.out.println("5:" + new Date().getTime());
        GridCoverage2D resampled = (GridCoverage2D) processor.doOperation(param);
        System.out.println("5:" + new Date().getTime());
        GridCoverage2D gc =
                gcf.create("name", resampled.getRenderedImage(), resampled.getEnvelope());

        GeoTiffWriter writer = null;

        try (ImageOutputStream imageOutStream =
                ImageIOExt.createImageOutputStream(image, outStream)) {
            if (imageOutStream == null) {
                throw new ServiceException("Unable to create ImageOutputStream.");
            }
            GeoTiffWriteParams wp = new GeoTiffWriteParams();
            wp.setCompressionMode(GeoTiffWriteParams.MODE_EXPLICIT);
            wp.setCompressionType("LZW");
            ParameterValueGroup params = new GeoTiffFormat().getWriteParameters();
            params.parameter(AbstractGridFormat.GEOTOOLS_WRITE_PARAMS.getName().toString())
                    .setValue(wp);

            writer = new GeoTiffWriter(imageOutStream);
            writer.write(
                    gc,
                    (GeneralParameterValue[])
                            params.values().toArray(new GeneralParameterValue[1]));
        } finally {
            try {
                if (writer != null) writer.dispose();
            } catch (Throwable e) {
                // eat exception to release resources silently
                if (LOGGER.isLoggable(Level.FINEST))
                    LOGGER.log(Level.FINEST, "Unable to properly dispose writer", e);
            }

            // let go of the chain behind the coverage
            // RasterCleaner.addCoverage(subCov);
        }
        long end = new Date().getTime();
        LOGGER.info("duration:" + (end - start));
    }

    /**
     * getFinalCoverage - message the RenderedImage into Bil
     *
     * @param request CoverageRequest
     * @param meta CoverageInfo
     * @param mapContent Context for GetMap request.
     * @param coverageReader reader
     * @return GridCoverage2D
     * @throws Exception an error occurred
     */
    private static GridCoverage2D getFinalCoverage(
            GetMapRequest request,
            MapLayerInfo meta,
            WMSMapContent mapContent,
            GridCoverage2DReader coverageReader,
            GeneralEnvelope destinationEnvelope)
            throws WcsException, IOException, IndexOutOfBoundsException, FactoryException,
                    TransformException {
        // This is the final Response CRS
        final String responseCRS = request.getSRS();

        // - then create the Coordinate Reference System
        final CoordinateReferenceSystem targetCRS = CRS.decode(responseCRS);

        // This is the CRS of the requested Envelope
        final String requestCRS = request.getSRS();

        // - then create the Coordinate Reference System
        final CoordinateReferenceSystem sourceCRS = CRS.decode(requestCRS);

        // This is the CRS of the Coverage Envelope
        final CoordinateReferenceSystem cvCRS =
                ((GeneralEnvelope) coverageReader.getOriginalEnvelope())
                        .getCoordinateReferenceSystem();

        // this is the destination envelope in the coverage crs
        final GeneralEnvelope destinationEnvelopeInSourceCRS =
                CRS.transform(destinationEnvelope, cvCRS);

        /** Reading Coverage on Requested Envelope */
        Rectangle destinationSize = null;
        destinationSize = new Rectangle(0, 0, request.getHeight(), request.getWidth());

        /** Checking for supported Interpolation Methods */
        Interpolation interpolation = Interpolation.getInstance(Interpolation.INTERP_BILINEAR);

        // /////////////////////////////////////////////////////////
        //
        // Reading the coverage
        //
        // /////////////////////////////////////////////////////////
        Map<Object, Object> parameters = new HashMap<Object, Object>();
        parameters.put(
                AbstractGridFormat.READ_GRIDGEOMETRY2D.getName().toString(),
                new GridGeometry2D(
                        new GeneralGridEnvelope(destinationSize), destinationEnvelopeInSourceCRS));

        final GridCoverage2D coverage =
                coverageReader.read(
                        CoverageUtils.getParameters(
                                coverageReader.getFormat().getReadParameters(), parameters, true));

        if (coverage == null) {
            LOGGER.log(Level.FINE, "Failed to read coverage - continuing");
            return null;
        }

        /** Band Select */
        /*
           Coverage bandSelectedCoverage = null;

           bandSelectedCoverage = WCSUtils.bandSelect(request.getParameters(), coverage);
        */
        /** Crop */
        final GridCoverage2D croppedGridCoverage =
                BandstifWCSUtils.crop(
                        coverage,
                        (GeneralEnvelope) coverage.getEnvelope(),
                        cvCRS,
                        destinationEnvelopeInSourceCRS,
                        Boolean.TRUE);

        /** Scale/Resampling (if necessary) */
        // GridCoverage2D subCoverage = null;
        GridCoverage2D subCoverage = croppedGridCoverage;
        final GeneralGridEnvelope newGridrange = new GeneralGridEnvelope(destinationSize);

        subCoverage =
                BandstifWCSUtils.scale(
                        croppedGridCoverage,
                        newGridrange,
                        croppedGridCoverage,
                        cvCRS,
                        destinationEnvelopeInSourceCRS);

        /** Reproject */
        subCoverage = BandstifWCSUtils.reproject(subCoverage, sourceCRS, targetCRS, interpolation);

        return subCoverage;
    }

    /** This is not really an image map */
    @Override
    public MapProducerCapabilities getCapabilities(String outputFormat) {
        // FIXME become more capable
        return new MapProducerCapabilities(false, false, false);
    }

    static {
        RecodeRaster.register(JAI.getDefaultInstance());
    }
}
