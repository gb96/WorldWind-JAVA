/*
Copyright (C) 2001, 2009 United States Government as represented by
the Administrator of the National Aeronautics and Space Administration.
All Rights Reserved.
*/
package gov.nasa.worldwind.applications.sar.render;

import gov.nasa.worldwind.geom.Line;
import gov.nasa.worldwind.geom.Position;
import gov.nasa.worldwind.geom.Vec4;
import gov.nasa.worldwind.render.DrawContext;
import gov.nasa.worldwind.render.OrderedRenderable;
import gov.nasa.worldwind.render.Renderable;
import gov.nasa.worldwind.util.Logging;

import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;

import javax.media.opengl.GL;
import javax.media.opengl.GL2;
import javax.media.opengl.fixedfunc.GLMatrixFunc;

/**
 * Display an horizontal line across the viewport when a plane at a given elevation cuts
 * through the view near plane.
 *
 * @author Patrick Murris
 * @version $Id: ScreenElevationLine.java 11489 2009-06-08 16:34:20Z dcollins $
 */
public class ScreenElevationLine implements Renderable
{
    private double elevation = 0;
    private Color color = Color.WHITE;
    private boolean enabled = true;

    /**
     * Get the line current elevation.
     *
     * @return the line current elevation.
     */
    public double getElevation()
    {
        return this.elevation;
    }

    /**
     * Set the line elevation.
     *
     * @param elevation the line elevation.
     */
    public void setElevation(double elevation)
    {
        this.elevation = elevation;
    }

    /**
     * Get the line color.
     *
     * @return the line color.
     */
    public Color getColor()
    {
        return this.color;
    }

    /**
     * Set the line color.
     *
     * @param color the line color.
     */
    public void setColor(Color color)
    {
        if (color == null)
        {
            String msg = Logging.getMessage("nullValue.ColorIsNull");
            Logging.logger().severe(msg);
            throw new IllegalArgumentException(msg);
        }

        this.color = color;
    }

    public boolean isEnabled()
    {
        return this.enabled;
    }

    public void setEnabled(boolean state)
    {
        this.enabled = state;
    }

    public void render(DrawContext dc)
    {
        if (this.isEnabled())
            dc.addOrderedRenderable(new OrderedItem());
    }

    private class OrderedItem implements OrderedRenderable
    {
        public double getDistanceFromEye()
        {
            return 1;
        }

        public void render(DrawContext dc)
        {
            draw(dc);
        }

        public void pick(DrawContext dc, Point pickPoint)
        {
            draw(dc);
        }
    }

    private void draw(DrawContext dc)
    {
        Double lineY = computeLineY(dc);
        if (lineY == null)
            return;

        GL2 gl = dc.getGL();

        boolean attribsPushed = false;
        boolean modelviewPushed = false;
        boolean projectionPushed = false;

        try
        {
            gl.glPushAttrib(GL.GL_DEPTH_BUFFER_BIT
                | GL.GL_COLOR_BUFFER_BIT
                | GL2.GL_ENABLE_BIT
                | GL2.GL_TEXTURE_BIT
                | GL2.GL_TRANSFORM_BIT
                | GL2.GL_VIEWPORT_BIT
                | GL2.GL_CURRENT_BIT);
            attribsPushed = true;

            gl.glEnable(GL.GL_BLEND);
            gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
            gl.glDisable(GL.GL_DEPTH_TEST);

            // Load a parallel projection with xy dimensions (viewportWidth, viewportHeight)
            // into the GL projection matrix.
            Rectangle viewport = dc.getView().getViewport();
            gl.glMatrixMode(GLMatrixFunc.GL_PROJECTION);
            gl.glPushMatrix();
            projectionPushed = true;
            gl.glLoadIdentity();
            gl.glOrtho(0d, viewport.width, 0d, viewport.height, -1, 1);

            gl.glMatrixMode(GLMatrixFunc.GL_MODELVIEW);
            gl.glPushMatrix();
            modelviewPushed = true;
            gl.glLoadIdentity();

            if (!dc.isPickingMode())
            {
                // Set color
                gl.glColor4ub((byte) this.color.getRed(), (byte) this.color.getGreen(),
                    (byte) this.color.getBlue(), (byte) this.color.getAlpha());
                gl.glDisable(GL.GL_TEXTURE_2D);        // no textures
            }

            // Draw line
            gl.glBegin(GL.GL_LINE_STRIP);
            gl.glVertex3d(0, lineY, 0);
            gl.glVertex3d(viewport.width, lineY, 0);
            gl.glEnd();

        }
        finally
        {
            if (projectionPushed)
            {
                gl.glMatrixMode(GLMatrixFunc.GL_PROJECTION);
                gl.glPopMatrix();
            }
            if (modelviewPushed)
            {
                gl.glMatrixMode(GLMatrixFunc.GL_MODELVIEW);
                gl.glPopMatrix();
            }
            if (attribsPushed)
                gl.glPopAttrib();
        }
    }

    private Double computeLineY(DrawContext dc)
    {
        Vec4 point = dc.getGlobe().computePointFromPosition(
            new Position(dc.getView().getEyePosition(), this.elevation));
        Vec4 direction = dc.getView().getForwardVector().perpendicularTo3(point); // Round globe only
        Vec4 intersection = dc.getView().getFrustumInModelCoordinates().getNear().intersect(new Line(point, direction));
        if (intersection != null)
        {
            Vec4 screenPoint = dc.getView().project(intersection);
            if (screenPoint != null)
                return screenPoint.y;
        }
        return null;
    }
}
