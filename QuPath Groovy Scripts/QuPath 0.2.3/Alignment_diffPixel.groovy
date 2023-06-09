/***
 * Script to align 2 images in the project with different pixel sizes, using either intensities or annotations.
 * Run from base image and include the name of the template image in otherFile.
 * Writes the affine transform to an object inside the Affine subfolder of your project folder.
 * Also grabs the detection objects from the template image. Can change this to annotation objects.
 *
 * Needed inputs: name of template image, what values to use for alignment (intensity or annotation), what type of registration (RIGID or AFFINE), what pixel sizes to use for iterative registration refinement. 
 *
 * Written by Sara McArdle of the La Jolla Institute, 2020, with lots of help from Mike Nelson.
 *
 */
import static qupath.lib.gui.scripting.QPEx.*

import qupath.lib.objects.PathCellObject
import qupath.lib.objects.PathDetectionObject
import qupath.lib.objects.PathObject
import qupath.lib.objects.PathObjects
import qupath.lib.objects.PathTileObject
import qupath.lib.objects.classes.PathClassFactory
import qupath.lib.roi.RoiTools
import qupath.lib.roi.interfaces.ROI
import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.images.servers.ImageServer
import qupath.lib.images.servers.PixelCalibration
import qupath.lib.regions.RegionRequest
import qupath.opencv.tools.OpenCVTools

import java.awt.Graphics2D
import java.awt.Transparency
import java.awt.color.ColorSpace
import java.awt.geom.AffineTransform
import java.awt.image.ComponentColorModel
import java.awt.image.DataBuffer
import java.awt.image.BufferedImage
import javafx.scene.transform.Affine

import org.bytedeco.javacpp.indexer.FloatIndexer
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.TermCriteria
import org.bytedeco.opencv.global.opencv_core
import org.bytedeco.opencv.global.opencv_video
import org.bytedeco.javacpp.indexer.Indexer

//set these things
String alignmentType="AREA"  //"AREA" will use annotations. Any other string will use intensities.
otherFile='Zinc_raw.tiff' //Name of template image

//collect basic information
baseImageName = getProjectEntry().getImageName()
def imageDataBase=getCurrentImageData()
def imageDataSelected=project.getImageList().find{it.getImageName()==otherFile}.readImageData()
double otherPixelSize=imageDataSelected.getServer().getPixelCalibration().getAveragedPixelSizeMicrons()


//perform affine transforms. Start with a large pixel size and iteratively refine the transform. NOTE: the final pixel size in the last autoAlignPrep SHOULD NOT be smaller than the pixel size of your least resolved image
Affine affine= []
autoAlignPrep(200.0,alignmentType,imageDataBase,imageDataSelected,affine,"RIGID")
autoAlignPrep(100.0,alignmentType,imageDataBase,imageDataSelected,affine,"RIGID")
def scaleFactor=autoAlignPrep(otherPixelSize,alignmentType,imageDataBase,imageDataSelected,affine,"AFFINE") //may want to change to RIGID, depending on your application.

//deal with the differing downsample amounts
affine.prependScale(1/scaleFactor,1/scaleFactor)

//save the transform as an object in the project folder
def matrix = []
matrix << affine.getMxx()
matrix << affine.getMxy()
matrix << affine.getTx()
matrix << affine.getMyx()
matrix << affine.getMyy()
matrix << affine.getTy()

affinepath = buildFilePath(PROJECT_BASE_DIR, 'Affine '+baseImageName)
mkdirs(affinepath)
path = buildFilePath(PROJECT_BASE_DIR, 'Affine '+baseImageName,  otherFile+".aff")

new File(path).withObjectOutputStream {
    it.writeObject(matrix)
}


//gather all annotation objects in the otherFile
new File(affinepath).eachFile{ f->
    GatherObjects(false, true, f)
}


/*Subfunctions taken from here:
https://github.com/qupath/qupath/blob/a1465014c458d510336993802efb08f440b50cc1/qupath-experimental/src/main/java/qupath/lib/gui/align/ImageAlignmentPane.java
 */

//creates an image server using the actual images (for intensity-based alignment) or a labeled image server (for annotation-based).
double autoAlignPrep(double requestedPixelSizeMicrons, String alignmentMethod, ImageData<BufferedImage> imageDataBase, ImageData<BufferedImage> imageDataSelected, Affine affine,String registrationType) throws IOException {
    ImageServer<BufferedImage> serverBase, serverSelected;

    if (alignmentMethod == 'AREA') {
        logger.debug("Image alignment using area annotations");
        Map<PathClass, Integer> labels = new LinkedHashMap<>();
        int label = 1;
        labels.put(PathClassFactory.getPathClassUnclassified(), label++);
        for (def annotation : imageDataBase.getHierarchy().getAnnotationObjects()) {
            def pathClass = annotation.getPathClass();
            if (pathClass != null && !labels.containsKey(pathClass))
                labels.put(pathClass, label++);
        }
        for (def annotation : imageDataSelected.getHierarchy().getAnnotationObjects()) {
            def pathClass = annotation.getPathClass();
            if (pathClass != null && !labels.containsKey(pathClass))
                labels.put(pathClass, label++);
        }

        double downsampleBase = requestedPixelSizeMicrons / imageDataBase.getServer().getPixelCalibration().getAveragedPixelSize().doubleValue();
        serverBase = new LabeledImageServer.Builder(imageDataBase)
                .backgroundLabel(0)
                .addLabels(labels)
                .downsample(downsampleBase)
                .build();

        double downsampleSelected = requestedPixelSizeMicrons / imageDataSelected.getServer().getPixelCalibration().getAveragedPixelSize().doubleValue();
        serverSelected = new LabeledImageServer.Builder(imageDataSelected)
                .backgroundLabel(0)
                .addLabels(labels)
                .downsample(downsampleSelected)
                .build();


    } else {
        // Default - just use intensities
        logger.debug("Image alignment using intensities");
        serverBase = imageDataBase.getServer();
        serverSelected = imageDataSelected.getServer();
    }

    scaleFactor=autoAlign(serverBase, serverSelected, registrationType, affine, requestedPixelSizeMicrons);
    return scaleFactor
}

double autoAlign(ImageServer<BufferedImage> serverBase, ImageServer<BufferedImage> serverOverlay, String regionstrationType, Affine affine, double requestedPixelSizeMicrons) {
    PixelCalibration calBase = serverBase.getPixelCalibration()
    double pixelSizeBase = calBase.getAveragedPixelSizeMicrons()
    double downsampleBase = 1
    if (!Double.isFinite(pixelSizeBase)) {
      //  while (serverBase.getWidth() / downsampleBase > 2000)
       //     downsampleBase++;
       // logger.warn("Pixel size is unavailable! Default downsample value of {} will be used", downsampleBase)
        pixelSizeBase=50
        downsampleBase = requestedPixelSizeMicrons / pixelSizeBase
    } else {
        downsampleBase = requestedPixelSizeMicrons / pixelSizeBase
    }

    PixelCalibration calOverlay = serverOverlay.getPixelCalibration()
    double pixelSizeOverlay = calOverlay.getAveragedPixelSizeMicrons()
    double downsampleOverlay = 1
    if (!Double.isFinite(pixelSizeOverlay)) {
    //    while (serverBase.getWidth() / downsampleOverlay > 2000)
    //        downsampleOverlay++;
     //   logger.warn("Pixel size is unavailable! Default downsample value of {} will be used", downsampleOverlay)
        pixelSizeOverlay=50
        downsampleOverlay = requestedPixelSizeMicrons / pixelSizeOverlay
    } else {
        downsampleOverlay = requestedPixelSizeMicrons / pixelSizeOverlay
    }

    double scaleFactor=downsampleBase/downsampleOverlay

    BufferedImage imgBase = serverBase.readBufferedImage(RegionRequest.createInstance(serverBase.getPath(), downsampleBase, 0, 0, serverBase.getWidth(), serverBase.getHeight()))
    BufferedImage imgOverlay = serverOverlay.readBufferedImage(RegionRequest.createInstance(serverOverlay.getPath(), downsampleOverlay, 0, 0, serverOverlay.getWidth(), serverOverlay.getHeight()))

    imgBase = ensureGrayScale(imgBase)
    imgOverlay = ensureGrayScale(imgOverlay)

    Mat matBase = OpenCVTools.imageToMat(imgBase)
    Mat matOverlay = OpenCVTools.imageToMat(imgOverlay)

    Mat matTransform = Mat.eye(2, 3, opencv_core.CV_32F).asMat()
// Initialize using existing transform
//		affine.setToTransform(mxx, mxy, tx, myx, myy, ty)
    try {
        FloatIndexer indexer = matTransform.createIndexer()
        indexer.put(0, 0, (float)affine.getMxx())
        indexer.put(0, 1, (float)affine.getMxy())
        indexer.put(0, 2, (float)(affine.getTx() / downsampleBase))
        indexer.put(1, 0, (float)affine.getMyx())
        indexer.put(1, 1, (float)affine.getMyy())
        indexer.put(1, 2, (float)(affine.getTy() / downsampleBase))
//			System.err.println(indexer)
    } catch (Exception e) {
        logger.error("Error closing indexer", e)
    }

    TermCriteria termCrit = new TermCriteria(TermCriteria.COUNT, 100, 0.0001)

    try {
        int motion
        switch (regionstrationType) {
            case "AFFINE":
                motion = opencv_video.MOTION_AFFINE
                break
            case "RIGID":
                motion = opencv_video.MOTION_EUCLIDEAN
                break
            default:
                logger.warn("Unknown registraton type {} - will use {}", regionstrationType, RegistrationType.AFFINE)
                motion = opencv_video.MOTION_AFFINE
                break
        }
        double result = opencv_video.findTransformECC(matBase, matOverlay, matTransform, motion, termCrit, null)
        logger.info("Transformation result: {}", result)
    } catch (Exception e) {
        Dialogs.showErrorNotification("Estimate transform", "Unable to estimated transform - result did not converge")
        logger.error("Unable to estimate transform", e)
        return
    }

// To use the following function, images need to be the same size
//		def matTransform = opencv_video.estimateRigidTransform(matBase, matOverlay, false);
    Indexer indexer = matTransform.createIndexer()
    affine.setToTransform(
            indexer.getDouble(0, 0),
            indexer.getDouble(0, 1),
            indexer.getDouble(0, 2) * downsampleBase,
            indexer.getDouble(1, 0),
            indexer.getDouble(1, 1),
            indexer.getDouble(1, 2) * downsampleBase
    )
    indexer.release()

    matBase.release()
    matOverlay.release()
    matTransform.release()

    return scaleFactor
}

//to gather detection objects instead of annotation, change line ~250 to def pathObjects = otherHierarchy.getDetectionObjects()
def GatherObjects(boolean deleteExisting, boolean createInverse, File f){
    f.withObjectInputStream {
        matrix = it.readObject()

        // Get the project & the requested image name
        def project = getProject()
        def entry = project.getImageList().find {it.getImageName()+".aff" == f.getName()}
        if (entry == null) {
            print 'Could not find image with name ' + f.getName()
            return
        }

        def otherHierarchy = entry.readHierarchy()
        def pathObjects = otherHierarchy.getDetectionObjects() //OR getAnnotationObjects()

        // Define the transformation matrix
        def transform = new AffineTransform(
                matrix[0], matrix[3], matrix[1],
                matrix[4], matrix[2], matrix[5]
        )
        if (createInverse)
            transform = transform.createInverse()

        if (deleteExisting)
            clearAllObjects()

        def newObjects = []
        for (pathObject in pathObjects) {
            newObjects << transformObject(pathObject, transform)
        }
        addObjects(newObjects)
    }
}

//other subfunctions

PathObject transformObject(PathObject pathObject, AffineTransform transform) {
    // Create a new object with the converted ROI
    def roi = pathObject.getROI()
    def roi2 = transformROI(roi, transform)
    def newObject = null
    if (pathObject instanceof PathCellObject) {
        def nucleusROI = pathObject.getNucleusROI()
        if (nucleusROI == null)
            newObject = PathObjects.createCellObject(roi2, pathObject.getPathClass(), pathObject.getMeasurementList())
        else
            newObject = PathObjects.createCellObject(roi2, transformROI(nucleusROI, transform), pathObject.getPathClass(), pathObject.getMeasurementList())
    } else if (pathObject instanceof PathTileObject) {
        newObject = PathObjects.createTileObject(roi2, pathObject.getPathClass(), pathObject.getMeasurementList())
    } else if (pathObject instanceof PathDetectionObject) {
        newObject = PathObjects.createDetectionObject(roi2, pathObject.getPathClass(), pathObject.getMeasurementList())
        newObject.setName(pathObject.getName())
    } else {
        newObject = PathObjects.createAnnotationObject(roi2, pathObject.getPathClass(), pathObject.getMeasurementList())
        newObject.setName(pathObject.getName())
    }
    // Handle child objects
    if (pathObject.hasChildren()) {
        newObject.addPathObjects(pathObject.getChildObjects().collect({transformObject(it, transform)}))
    }
    return newObject
}

ROI transformROI(ROI roi, AffineTransform transform) {
    def shape = RoiTools.getShape(roi) // Should be able to use roi.getShape() - but there's currently a bug in it for rectangles/ellipses!
    shape2 = transform.createTransformedShape(shape)
    return RoiTools.getShapeROI(shape2, roi.getImagePlane(), 0.5)
}

static BufferedImage ensureGrayScale(BufferedImage img) {
    if (img.getType() == BufferedImage.TYPE_BYTE_GRAY)
        return img
    if (img.getType() == BufferedImage.TYPE_BYTE_INDEXED) {
        ColorSpace cs = ColorSpace.getInstance(ColorSpace.CS_GRAY)
        def colorModel = new ComponentColorModel(cs, 8 as int[], false, true,
                Transparency.OPAQUE,
                DataBuffer.TYPE_BYTE)
        return new BufferedImage(colorModel, img.getRaster(), false, null)
    }
    BufferedImage imgGray = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_BYTE_GRAY)
    Graphics2D g2d = imgGray.createGraphics()
    g2d.drawImage(img, 0, 0, null)
    g2d.dispose()
    return imgGray
}