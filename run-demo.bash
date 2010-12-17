#!/bin/bash
# Run a WorldWind Demo
# $Id: run-demo.bash 14013 2010-10-22 20:10:42Z garakl $

echo Running $1
java -Xmx512m -Dsun.java2d.noddraw=true -classpath ./src:./classes:./worldwind.jar:./jogl.jar:./gluegen-rt.jar:./gdal.jar $*
