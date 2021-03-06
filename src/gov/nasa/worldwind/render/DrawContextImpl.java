/*
Copyright (C) 2001, 2006 United States Government
as represented by the Administrator of the
National Aeronautics and Space Administration.
All Rights Reserved.
*/
package gov.nasa.worldwind.render;

import com.sun.opengl.util.texture.TextureCoords;
import gov.nasa.worldwind.*;
import gov.nasa.worldwind.cache.TextureCache;
import gov.nasa.worldwind.geom.*;
import gov.nasa.worldwind.globes.Globe;
import gov.nasa.worldwind.layers.*;
import gov.nasa.worldwind.pick.*;
import gov.nasa.worldwind.terrain.*;
import gov.nasa.worldwind.util.*;

import javax.media.opengl.*;
import javax.media.opengl.glu.GLU;
import java.awt.*;
import java.nio.FloatBuffer;
import java.util.*;
import java.util.List;
import java.util.Queue;

/**
 * @author Tom Gaskins
 * @version $Id: DrawContextImpl.java 14193 2010-12-06 22:20:43Z dcollins $
 */
public class DrawContextImpl extends WWObjectImpl implements DrawContext
{
    private long frameTimestamp;
    private GLContext glContext;
    private GLRuntimeCapabilities glRuntimeCaps;
    private GLU glu = new GLU();
    private View view;
    private Model model;
    private Globe globe;
    private double verticalExaggeration = 1d;
    private Sector visibleSector;
    private SectorGeometryList surfaceGeometry;
    private PickedObjectList pickedObjects = new PickedObjectList();
    private int uniquePickNumber = 0;
    private Color clearColor = new Color(0, 0, 0, 0);
    private boolean pickingMode = false;
    private boolean deepPickingMode = false;
    private boolean isOrderedRenderingMode = false;
    private Point pickPoint = null;
    private Point viewportCenterScreenPoint = null;
    private Position viewportCenterPosition = null;
    private SurfaceTileRenderer geographicSurfaceTileRenderer = new GeographicSurfaceTileRenderer();
    private AnnotationRenderer annotationRenderer = new BasicAnnotationRenderer();
    private TextureCache textureCache;
    private TextRendererCache textRendererCache;
    private Set<String> perFrameStatisticsKeys;
    private Collection<PerformanceStatistic> perFrameStatistics;
    private SectorVisibilityTree visibleSectors;
    private Layer currentLayer;
    private int redrawRequested = 0;
    private PickPointFrustumList pickFrustumList = new PickPointFrustumList();
    private Collection<Throwable> renderingExceptions;
    private Dimension pickPointFrustumDimension = new Dimension(3, 3);
    protected LightingModel standardLighting = new BasicLightingModel();
    protected OGLStackHandler lightingStackHandler = new OGLStackHandler();

    protected static class OrderedRenderableEntry
    {
        protected OrderedRenderable or;
        protected long time;

        public OrderedRenderableEntry(OrderedRenderable orderedRenderable, long insertionTime)
        {
            this.or = orderedRenderable;
            this.time = insertionTime;
        }
    }

    protected PriorityQueue<OrderedRenderableEntry> orderedRenderables =
        new PriorityQueue<OrderedRenderableEntry>(100, new Comparator<OrderedRenderableEntry>()
        {
            public int compare(OrderedRenderableEntry orA, OrderedRenderableEntry orB)
            {
                double eA = orA.or.getDistanceFromEye();
                double eB = orB.or.getDistanceFromEye();

                return eA > eB ? -1 : eA == eB ? (orA.time < orB.time ? -1 : orA.time == orB.time ? 0 : 1) : 1;
            }
        });
    // Use a standard Queue to store the ordered surface object renderables. Ordered surface renderables are processed
    // in the order they were submitted.
    protected Queue<OrderedRenderable> orderedSurfaceRenderables = new ArrayDeque<OrderedRenderable>();

    /**
     * Free internal resources held by this draw context. A GL context must be current when this method is called.
     *
     * @throws javax.media.opengl.GLException - If an OpenGL context is not current when this method is called.
     */
    public void dispose()
    {
        this.geographicSurfaceTileRenderer.dispose();
    }

    public final GL getGL()
    {
        return this.getGLContext().getGL();
    }

    public final GLU getGLU()
    {
        return this.glu;
    }

    public final GLContext getGLContext()
    {
        return this.glContext;
    }

    public final int getDrawableHeight()
    {
        return this.getGLDrawable().getHeight();
    }

    public final int getDrawableWidth()
    {
        return this.getGLDrawable().getWidth();
    }

    public final GLDrawable getGLDrawable()
    {
        return this.getGLContext().getGLDrawable();
    }

    public GLRuntimeCapabilities getGLRuntimeCapabilities()
    {
        return this.glRuntimeCaps;
    }

    public void setGLRuntimeCapabilities(GLRuntimeCapabilities capabilities)
    {
        if (capabilities == null)
        {
            String message = Logging.getMessage("nullValue.GLRuntimeCapabilitiesIsNull");
            Logging.logger().severe(message);
            throw new IllegalArgumentException(message);
        }

        this.glRuntimeCaps = capabilities;
    }

    public final void initialize(GLContext glContext)
    {
        if (glContext == null)
        {
            String message = Logging.getMessage("nullValue.GLContextIsNull");
            Logging.logger().severe(message);
            throw new IllegalArgumentException(message);
        }

        this.glContext = glContext;

        this.visibleSector = null;
        if (this.surfaceGeometry != null)
            this.surfaceGeometry.clear();
        this.surfaceGeometry = null;

        this.pickedObjects.clear();
        this.orderedRenderables.clear();
        this.orderedSurfaceRenderables.clear();
        this.uniquePickNumber = 0;
        this.deepPickingMode = false;

        this.pickFrustumList.clear();

        this.currentLayer = null;
    }

    public final void setModel(Model model)
    {
        this.model = model;
        if (this.model == null)
            return;

        Globe g = this.model.getGlobe();
        if (g != null)
            this.globe = g;
    }

    public final Model getModel()
    {
        return this.model;
    }

    public final LayerList getLayers()
    {
        return this.model.getLayers();
    }

    public final Sector getVisibleSector()
    {
        return this.visibleSector;
    }

    public final void setVisibleSector(Sector s)
    {
        // don't check for null - it is possible that no globe is active, no view is active, no sectors visible, etc.
        this.visibleSector = s;
    }

    public void setSurfaceGeometry(SectorGeometryList surfaceGeometry)
    {
        this.surfaceGeometry = surfaceGeometry;
    }

    public SectorGeometryList getSurfaceGeometry()
    {
        return surfaceGeometry;
    }

    public final Globe getGlobe()
    {
        return this.globe != null ? this.globe : this.model.getGlobe();
    }

    public final void setView(View view)
    {
        this.view = view;
    }

    public final View getView()
    {
        return this.view;
    }

    public final void setGLContext(GLContext glContext)
    {
        if (glContext == null)
        {
            String message = Logging.getMessage("nullValue.GLContextIsNull");
            Logging.logger().severe(message);
            throw new IllegalArgumentException(message);
        }

        this.glContext = glContext;
    }

    public final double getVerticalExaggeration()
    {
        return verticalExaggeration;
    }

    public final void setVerticalExaggeration(double verticalExaggeration)
    {
        this.verticalExaggeration = verticalExaggeration;
    }

    public TextureCache getTextureCache()
    {
        return textureCache;
    }

    public void setTextureCache(TextureCache textureCache)
    {
        if (textureCache == null)
        {
            String msg = Logging.getMessage("nullValue.TextureCacheIsNull");
            Logging.logger().severe(msg);
            throw new IllegalArgumentException(msg);
        }

        this.textureCache = textureCache;
    }

    public TextRendererCache getTextRendererCache()
    {
        return textRendererCache;
    }

    public void setTextRendererCache(TextRendererCache textRendererCache)
    {
        if (textRendererCache == null)
        {
            String msg = Logging.getMessage("nullValue.TextRendererCacheIsNull");
            Logging.logger().severe(msg);
            throw new IllegalArgumentException(msg);
        }

        this.textRendererCache = textRendererCache;
    }

    public AnnotationRenderer getAnnotationRenderer()
    {
        return annotationRenderer;
    }

    public void setAnnotationRenderer(AnnotationRenderer ar)
    {
        if (ar == null)
        {
            String msg = Logging.getMessage("nullValue.RendererIsNull");
            Logging.logger().severe(msg);
            throw new IllegalArgumentException(msg);
        }
        annotationRenderer = ar;
    }

    public LightingModel getStandardLightingModel()
    {
        return standardLighting;
    }

    public void setStandardLightingModel(LightingModel standardLighting)
    {
        this.standardLighting = standardLighting;
    }

    public Point getPickPoint()
    {
        return pickPoint;
    }

    public void setPickPoint(Point pickPoint)
    {
        this.pickPoint = pickPoint;
    }

    public Point getViewportCenterScreenPoint()
    {
        return viewportCenterScreenPoint;
    }

    public void setViewportCenterScreenPoint(Point viewportCenterScreenPoint)
    {
        this.viewportCenterScreenPoint = viewportCenterScreenPoint;
    }

    public Position getViewportCenterPosition()
    {
        return viewportCenterPosition;
    }

    public void setViewportCenterPosition(Position viewportCenterPosition)
    {
        this.viewportCenterPosition = viewportCenterPosition;
    }

    public void addPickedObjects(PickedObjectList pickedObjects)
    {
        if (pickedObjects == null)
        {
            String msg = Logging.getMessage("nullValue.PickedObjectList");
            Logging.logger().severe(msg);
            throw new IllegalArgumentException(msg);
        }

        if (this.pickedObjects == null)
        {
            this.pickedObjects = pickedObjects;
            return;
        }

        for (PickedObject po : pickedObjects)
        {
            this.pickedObjects.add(po);
        }
    }

    public void addPickedObject(PickedObject pickedObject)
    {
        if (null == pickedObject)
        {
            String msg = Logging.getMessage("nullValue.PickedObject");
            Logging.logger().severe(msg);
            throw new IllegalArgumentException(msg);
        }

        if (null == this.pickedObjects)
            this.pickedObjects = new PickedObjectList();

        this.pickedObjects.add(pickedObject);
    }

    public PickedObjectList getPickedObjects()
    {
        return this.pickedObjects;
    }

    public Color getUniquePickColor()
    {
        this.uniquePickNumber++;
        int clearColorCode = this.getClearColor().getRGB();

        if (clearColorCode == this.uniquePickNumber)
            this.uniquePickNumber++;

        if (this.uniquePickNumber >= 0x00FFFFFF)
        {
            this.uniquePickNumber = 1;  // no black, no white
            if (clearColorCode == this.uniquePickNumber)
                this.uniquePickNumber++;
        }

        return new Color(this.uniquePickNumber, true); // has alpha
    }

    public Color getClearColor()
    {
        return this.clearColor;
    }

    public boolean isPickingMode()
    {
        return this.pickingMode;
    }

    public void enablePickingMode()
    {
        this.pickingMode = true;
    }

    public void disablePickingMode()
    {
        this.pickingMode = false;
    }

    public boolean isDeepPickingEnabled()
    {
        return this.deepPickingMode;
    }

    public void setDeepPickingEnabled(boolean tf)
    {
        this.deepPickingMode = tf;
    }

    public boolean isOrderedRenderingMode()
    {
        return this.isOrderedRenderingMode;
    }

    public void setOrderedRenderingMode(boolean tf)
    {
        this.isOrderedRenderingMode = tf;
    }

    public void addOrderedRenderable(OrderedRenderable orderedRenderable)
    {
        if (null == orderedRenderable)
        {
            String msg = Logging.getMessage("nullValue.OrderedRenderable");
            Logging.logger().warning(msg);
            return; // benign event
        }

        this.orderedRenderables.add(new OrderedRenderableEntry(orderedRenderable, System.nanoTime()));
    }

    public OrderedRenderable peekOrderedRenderables()
    {
        OrderedRenderableEntry ore = this.orderedRenderables.peek();

        return ore != null ? ore.or : null;
    }

    public OrderedRenderable pollOrderedRenderables()
    {
        OrderedRenderableEntry ore = this.orderedRenderables.poll();

        return ore != null ? ore.or : null;
    }

    /** {@inheritDoc} */
    public void addOrderedSurfaceRenderable(OrderedRenderable orderedRenderable)
    {
        if (orderedRenderable == null)
        {
            String msg = Logging.getMessage("nullValue.OrderedRenderable");
            Logging.logger().warning(msg);
            return; // benign event
        }

        this.orderedSurfaceRenderables.add(orderedRenderable);
    }

    /** {@inheritDoc} */
    public Queue<OrderedRenderable> getOrderedSurfaceRenderables()
    {
        return this.orderedSurfaceRenderables;
    }

    public void drawUnitQuad()
    {
        GL gl = this.getGL();

        gl.glBegin(GL.GL_QUADS);
        gl.glVertex2d(0d, 0d);
        gl.glVertex2d(1, 0d);
        gl.glVertex2d(1, 1);
        gl.glVertex2d(0d, 1);
        gl.glEnd();
    }

    public void drawUnitQuad(TextureCoords texCoords)
    {
        GL gl = this.getGL();

        gl.glBegin(GL.GL_QUADS);
        gl.glTexCoord2d(texCoords.left(), texCoords.bottom());
        gl.glVertex2d(0d, 0d);
        gl.glTexCoord2d(texCoords.right(), texCoords.bottom());
        gl.glVertex2d(1, 0d);
        gl.glTexCoord2d(texCoords.right(), texCoords.top());
        gl.glVertex2d(1, 1);
        gl.glTexCoord2d(texCoords.left(), texCoords.top());
        gl.glVertex2d(0d, 1);
        gl.glEnd();
    }

    public void drawUnitQuadOutline()
    {
        GL gl = this.getGL();

        gl.glBegin(GL.GL_LINE_LOOP);
        gl.glVertex2d(0d, 0d);
        gl.glVertex2d(1, 0d);
        gl.glVertex2d(1, 1);
        gl.glVertex2d(0d, 1);
        gl.glEnd();
    }

    public void drawNormals(float length, FloatBuffer vBuf, FloatBuffer nBuf)
    {
        if (vBuf == null || nBuf == null)
            return;

        GL gl = this.getGL();

        vBuf.rewind();
        nBuf.rewind();

        gl.glBegin(GL.GL_LINES);

        while (vBuf.hasRemaining())
        {
            float x = vBuf.get();
            float y = vBuf.get();
            float z = vBuf.get();
            float nx = nBuf.get() * length;
            float ny = nBuf.get() * length;
            float nz = nBuf.get() * length;

            gl.glVertex3f(x, y, z);
            gl.glVertex3f(x + nx, y + ny, z + nz);
        }

        gl.glEnd();
    }

    public Vec4 getPointOnTerrain(Angle latitude, Angle longitude)
    {
        if (latitude == null || longitude == null)
        {
            String message = Logging.getMessage("nullValue.LatitudeOrLongitudeIsNull");
            Logging.logger().severe(message);
            throw new IllegalArgumentException(message);
        }

        if (this.getVisibleSector() == null)
            return null;

        if (!this.getVisibleSector().contains(latitude, longitude))
            return null;

        SectorGeometryList sectorGeometry = this.getSurfaceGeometry();
        if (sectorGeometry != null)
        {
            Vec4 p = sectorGeometry.getSurfacePoint(latitude, longitude);
            if (p != null)
                return p;
        }

        return null;
    }

    public SurfaceTileRenderer getGeographicSurfaceTileRenderer()
    {
        return this.geographicSurfaceTileRenderer;
    }

    public Collection<PerformanceStatistic> getPerFrameStatistics()
    {
        return this.perFrameStatistics;
    }

    public void setPerFrameStatisticsKeys(Set<String> statKeys, Collection<PerformanceStatistic> stats)
    {
        this.perFrameStatisticsKeys = statKeys;
        this.perFrameStatistics = stats;
    }

    public Set<String> getPerFrameStatisticsKeys()
    {
        return perFrameStatisticsKeys;
    }

    public void setPerFrameStatistic(String key, String displayName, Object value)
    {
        if (this.perFrameStatistics == null || this.perFrameStatisticsKeys == null)
            return;

        if (key == null)
        {
            String message = Logging.getMessage("nullValue.KeyIsNull");
            Logging.logger().severe(message);
            throw new IllegalArgumentException(message);
        }

        if (displayName == null)
        {
            String message = Logging.getMessage("nullValue.DisplayNameIsNull");
            Logging.logger().severe(message);
            throw new IllegalArgumentException(message);
        }

        if (this.perFrameStatisticsKeys.contains(key) || this.perFrameStatisticsKeys.contains(PerformanceStatistic.ALL))
            this.perFrameStatistics.add(new PerformanceStatistic(key, displayName, value));
    }

    public void setPerFrameStatistics(Collection<PerformanceStatistic> stats)
    {
        if (this.perFrameStatistics == null || this.perFrameStatisticsKeys == null)
            return;

        if (stats == null)
        {
            String message = Logging.getMessage("nullValue.KeyIsNull"); // TODO
            Logging.logger().severe(message);
            throw new IllegalArgumentException(message);
        }

        for (PerformanceStatistic stat : stats)
        {
            this.perFrameStatistics.add(stat);
        }
    }

    public long getFrameTimeStamp()
    {
        return this.frameTimestamp;
    }

    public void setFrameTimeStamp(long frameTimeStamp)
    {
        this.frameTimestamp = frameTimeStamp;
    }

    public List<Sector> getVisibleSectors(double[] resolutions, long timeLimit, Sector sector)
    {
        if (resolutions == null)
        {
            String message = Logging.getMessage("nullValue.ArrayIsNull");
            Logging.logger().severe(message);
            throw new IllegalArgumentException(message);
        }

        if (timeLimit <= 0)
        {
            String message = Logging.getMessage("generic.TimeNegative", timeLimit);
            Logging.logger().severe(message);
            throw new IllegalArgumentException(message);
        }

        if (sector == null)
            sector = this.visibleSector;

        if (this.visibleSectors == null)
            this.visibleSectors = new SectorVisibilityTree();
        else if (this.visibleSectors.getSectorSize() == resolutions[resolutions.length - 1]
            && this.visibleSectors.getTimeStamp() == this.frameTimestamp)
            return this.visibleSectors.getSectors();

        long start = System.currentTimeMillis();
        List<Sector> sectors = this.visibleSectors.refresh(this, resolutions[0], sector);
        for (int i = 1; i < resolutions.length && (System.currentTimeMillis() < start + timeLimit); i++)
        {
            sectors = this.visibleSectors.refresh(this, resolutions[i], sectors);
        }

        this.visibleSectors.setTimeStamp(this.frameTimestamp);

        return this.visibleSectors.getSectors();
    }

    public void setCurrentLayer(Layer layer)
    {
        this.currentLayer = layer;
    }

    public Layer getCurrentLayer()
    {
        return this.currentLayer;
    }

    private LinkedHashMap<ScreenCredit, Long> credits = new LinkedHashMap<ScreenCredit, Long>();

    public void addScreenCredit(ScreenCredit credit)
    {
        this.credits.put(credit, this.frameTimestamp);
    }

    public Map<ScreenCredit, Long> getScreenCredits()
    {
        return this.credits;
    }

    public int getRedrawRequested()
    {
        return redrawRequested;
    }

    public void setRedrawRequested(int redrawRequested)
    {
        this.redrawRequested = redrawRequested;
    }

    public PickPointFrustumList getPickFrustums()
    {
        return this.pickFrustumList;
    }

    public void setPickPointFrustumDimension(Dimension dim)
    {
        if (dim == null)
        {
            String message = Logging.getMessage("nullValue.DimensionIsNull");
            Logging.logger().severe(message);
            throw new IllegalArgumentException(message);
        }

        if (dim.width < 3 || dim.height < 3)
        {
            String message = Logging.getMessage("DrawContext.PickPointFrustumDimensionTooSmall");
            Logging.logger().severe(message);
            throw new IllegalArgumentException(message);
        }

        this.pickPointFrustumDimension = new Dimension(dim);
    }

    public Dimension getPickPointFrustumDimension()
    {
        return this.pickPointFrustumDimension;
    }

    public void addPickPointFrustum()
    {
        //Compute the current picking frustum
        if (getPickPoint() != null)
        {
            Rectangle viewport = getView().getViewport();

            double viewportWidth = viewport.getWidth() <= 0.0 ? 1.0 : viewport.getWidth();
            double viewportHeight = viewport.getHeight() <= 0.0 ? 1.0 : viewport.getHeight();

            //Get the pick point and translate screen center to zero
            Point ptCenter = new Point(getPickPoint());
            ptCenter.y = (int) viewportHeight - ptCenter.y;
            ptCenter.translate((int) (-viewportWidth / 2), (int) (-viewportHeight / 2));

            //Number of pixels around pick point to include in frustum
            int offsetX = pickPointFrustumDimension.width / 2;
            int offsetY = pickPointFrustumDimension.height / 2;

            //If the frustum is not valid then don't add it and return silently
            if (offsetX == 0 || offsetY == 0)
                return;

            //Compute the distance to the near plane in screen coordinates
            double width = getView().getFieldOfView().tanHalfAngle() * getView().getNearClipDistance();
            double x = width / (viewportWidth / 2.0);
            double screenDist = getView().getNearClipDistance() / x;

            //Create the four vectors that define the top-left, top-right, bottom-left, and bottom-right vectors
            Vec4 vTL = new Vec4(ptCenter.x - offsetX, ptCenter.y + offsetY, -screenDist);
            Vec4 vTR = new Vec4(ptCenter.x + offsetX, ptCenter.y + offsetY, -screenDist);
            Vec4 vBL = new Vec4(ptCenter.x - offsetX, ptCenter.y - offsetY, -screenDist);
            Vec4 vBR = new Vec4(ptCenter.x + offsetX, ptCenter.y - offsetY, -screenDist);

            //Compute the frustum from these four vectors
            Frustum frustum = Frustum.fromPerspectiveVecs(vTL, vTR, vBL, vBR,
                getView().getNearClipDistance(), getView().getFarClipDistance());

            //Create the screen Rectangle associtated with this frustum
            Rectangle rectScreen = new Rectangle(getPickPoint().x - offsetX,
                (int) viewportHeight - getPickPoint().y - offsetY,
                pickPointFrustumDimension.width,
                pickPointFrustumDimension.height);

            //Transform the frustum to Model Coordinates
            Matrix modelviewTranspose = getView().getModelviewMatrix().getTranspose();
            if (modelviewTranspose != null)
                frustum = frustum.transformBy(modelviewTranspose);

            this.pickFrustumList.add(new PickPointFrustum(frustum, rectScreen));
        }
    }

    public Collection<Throwable> getRenderingExceptions()
    {
        return this.renderingExceptions;
    }

    public void setRenderingExceptions(Collection<Throwable> exceptions)
    {
        this.renderingExceptions = exceptions;
    }

    public void addRenderingException(Throwable t)
    {
        // If the renderingExceptions Collection is non-null, it's used as the data structure that accumulates rendering
        // exceptions. Otherwise this DrawContext ignores all rendering exceptions passed to this method.
        if (this.renderingExceptions == null)
            return;

        if (t == null)
        {
            String message = Logging.getMessage("nullValue.ThrowableIsNull");
            Logging.logger().severe(message);
            throw new IllegalArgumentException(message);
        }

        this.renderingExceptions.add(t);
    }

    public void pushProjectionOffest(Double offset)
    {
        // Modify the projection transform to shift the depth values slightly toward the camera in order to
        // ensure the lines are selected during depth buffering.
        GL gl = this.getGL();

        float[] pm = new float[16];
        gl.glGetFloatv(GL.GL_PROJECTION_MATRIX, pm, 0);
        pm[10] *= offset != null ? offset : 0.99; // TODO: See Lengyel 2 ed. Section 9.1.2 to compute optimal offset

        gl.glPushAttrib(GL.GL_TRANSFORM_BIT);
        gl.glMatrixMode(GL.GL_PROJECTION);
        gl.glPushMatrix();
        gl.glLoadMatrixf(pm, 0);
    }

    public void popProjectionOffest()
    {
        GL gl = this.getGL();

        gl.glMatrixMode(GL.GL_PROJECTION);
        gl.glPopMatrix();
        gl.glPopAttrib();
    }

    public static final float DEFAULT_DEPTH_OFFSET_FACTOR = 1f;
    public static final float DEFAULT_DEPTH_OFFSET_UNITS = 1f;

    public void drawOutlinedShape(OutlinedShape renderer, Object shape)
    {
        // Draw the outlined shape using a multiple pass algorithm. The motivation for this algorithm is twofold:
        //
        // * The outline appears both in front of and behind the shape. If the outline is drawn using GL line smoothing
        // or GL blending, then either the line must be broken into two parts, or rendered in two passes.
        //
        // * If depth offset is enabled, we want draw the shape on top of other intersecting shapes with similar depth
        // values to eliminate z-fighting between shapes. However we do not wish to offset both the depth and color
        // values, which would cause a cascading increase in depth offset when many shapes are drawn.
        //
        // These issues are resolved by making several passes for the interior and outline, as follows:

        GL gl = this.getGL();

        if (this.isDeepPickingEnabled())
        {
            if (renderer.isDrawInterior(this, shape))
                renderer.drawInterior(this, shape);

            if (renderer.isDrawOutline(this, shape)) // the line might extend outside the interior's projection
                renderer.drawOutline(this, shape);

            return;
        }

        OGLStackHandler ogsh = new OGLStackHandler();
        int attribMask = GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT | GL.GL_POLYGON_BIT;
        ogsh.pushAttrib(gl, attribMask);

        try
        {
            gl.glEnable(GL.GL_DEPTH_TEST);
            gl.glDepthFunc(GL.GL_LEQUAL);

            // If the outline and interior are enabled, then draw the outline but do not affect the depth buffer. The
            // fill pixels contribute the depth values. When the interior is drawn, it draws on top of these colors, and
            // the outline is be visible behind the potentially transparent interior.
            if (renderer.isDrawOutline(this, shape) && renderer.isDrawInterior(this, shape))
            {
                gl.glColorMask(true, true, true, true);
                gl.glDepthMask(false);

                renderer.drawOutline(this, shape);
            }

            // If the interior is enabled, then make two passes as follows. The first pass draws the interior depth
            // values with a depth offset (likely away from the eye). This enables the shape to contribute to the
            // depth buffer and occlude other geometries as it normally would. The second pass draws the interior color
            // values without a depth offset, and does not affect the depth buffer. This giving the shape outline depth
            // priority over the fill, and gives the fill depth priority over other shapes drawn with depth offset
            // enabled. By drawing the colors without depth offset, we avoid the problem of having to use ever
            // increasing depth offsets.
            if (renderer.isDrawInterior(this, shape))
            {
                if (renderer.isEnableDepthOffset(this, shape))
                {
                    // Draw depth.
                    gl.glColorMask(false, false, false, false);
                    gl.glDepthMask(true);
                    gl.glEnable(GL.GL_POLYGON_OFFSET_FILL);
                    Double depthOffsetFactor = renderer.getDepthOffsetFactor(this, shape);
                    Double depthOffsetUnits = renderer.getDepthOffsetUnits(this, shape);
                    gl.glPolygonOffset(
                        depthOffsetFactor != null ? depthOffsetFactor.floatValue() : DEFAULT_DEPTH_OFFSET_FACTOR,
                        depthOffsetUnits != null ? depthOffsetUnits.floatValue() : DEFAULT_DEPTH_OFFSET_UNITS);

                    renderer.drawInterior(this, shape);

                    // Draw color.
                    gl.glColorMask(true, true, true, true);
                    gl.glDepthMask(false);
                    gl.glDisable(GL.GL_POLYGON_OFFSET_FILL);

                    renderer.drawInterior(this, shape);
                }
                else
                {
                    gl.glColorMask(true, true, true, true);
                    gl.glDepthMask(true);

                    renderer.drawInterior(this, shape);
                }
            }

            // If the outline is enabled, then draw the outline color and depth values. This blends outline colors with
            // the interior colors.
            if (renderer.isDrawOutline(this, shape))
            {
                gl.glColorMask(true, true, true, true);
                gl.glDepthMask(true);

                renderer.drawOutline(this, shape);
            }
        }
        finally
        {
            ogsh.pop(gl);
        }
    }

    public void beginStandardLighting()
    {
        if (this.standardLighting != null)
        {
            this.standardLighting.beginLighting(this);
            this.getGL().glEnable(GL.GL_LIGHTING);
        }
    }

    public void endStandardLighting()
    {
        if (this.standardLighting != null)
        {
            this.standardLighting.endLighting(this);
        }
    }

    public boolean isSmall(Extent extent, int numPixels)
    {
        return extent != null && extent.getDiameter() <= numPixels * this.getView().computePixelSizeAtDistance(
            this.getView().getEyePoint().distanceTo3(extent.getCenter()));
    }

    public Terrain getTerrain()
    {
        return this.terrain;
    }

    public Vec4 computeTerrainPoint(Angle lat, Angle lon, double offset)
    {
        return this.getTerrain().getSurfacePoint(lat, lon, offset);
    }

    protected Terrain terrain = new Terrain()
    {
        public Globe getGlobe()
        {
            return DrawContextImpl.this.getGlobe();
        }

        public double getVerticalExaggeration()
        {
            return DrawContextImpl.this.getVerticalExaggeration();
        }

        public Vec4 getSurfacePoint(Position position)
        {
            if (position == null)
            {
                String msg = Logging.getMessage("nullValue.PositionIsNull");
                Logging.logger().severe(msg);
                throw new IllegalArgumentException(msg);
            }

            SectorGeometryList sectorGeometry = DrawContextImpl.this.getSurfaceGeometry();
            if (sectorGeometry == null)
                return null;

            Vec4 pt = sectorGeometry.getSurfacePoint(position);
            if (pt == null)
            {
                double elevation = this.getGlobe().getElevation(position.getLatitude(), position.getLongitude());
                pt = this.getGlobe().computePointFromPosition(position,
                    position.getAltitude() + elevation * this.getVerticalExaggeration());
            }

            return pt;
        }

        public Vec4 getSurfacePoint(Angle latitude, Angle longitude, double metersOffset)
        {
            if (latitude == null || longitude == null)
            {
                String msg = Logging.getMessage("nullValue.LatLonIsNull");
                Logging.logger().severe(msg);
                throw new IllegalArgumentException(msg);
            }

            SectorGeometryList sectorGeometry = DrawContextImpl.this.getSurfaceGeometry();
            if (sectorGeometry == null)
                return null;

            Vec4 pt = sectorGeometry.getSurfacePoint(latitude, longitude, metersOffset);

            if (pt == null)
            {
                double elevation = this.getGlobe().getElevation(latitude, longitude);
                pt = this.getGlobe().computePointFromPosition(latitude, longitude,
                    metersOffset + elevation * this.getVerticalExaggeration());
            }

            return pt;
        }

        public Intersection[] intersect(Position pA, Position pB)
        {
            SectorGeometryList sectorGeometry = DrawContextImpl.this.getSurfaceGeometry();
            if (sectorGeometry == null)
                return null;

            Vec4 ptA = this.getSurfacePoint(pA);
            Vec4 ptB = this.getSurfacePoint(pB);

            if (pA == null || pB == null)
                return null;

            return sectorGeometry.intersect(new Line(ptA, ptB.subtract3(ptA)));
        }
    };
}
