/*
 * #%L
 * Various plugins for ImageJ.
 * %%
 * Copyright (C) 2010 - 2014 Board of Regents of the University of
 * Wisconsin-Madison.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

package loci.plugins.stitching;

/**
 * A simple data structure for storing metadata about a tile.
 * 
 * @author Curtis Rueden
 */
public class Pt {

	public int i, p;
	public double w, h;
	public int theC, theZ, theT;
	public double x, y, z;
	public int t;

	public Pt() {}

	public Pt(final int i, final double w, final double h, final Double x,
		final Double y, final Double z)
	{
		this(i, -1, null, null, null, w, h, x, y, z, null);
	}

	public Pt(final int i, final int p, final Integer theC, final Integer theZ,
		final Integer theT, final double w, final double h, final Double x,
		final Double y, final Double z, final Integer t)
	{
		this.i = i;
		this.p = p;
		this.theC = theC == null ? -1 : theC;
		this.theZ = theZ == null ? -1 : theZ;
		this.theT = theT == null ? -1 : theT;
		this.w = w;
		this.h = h;
		this.x = x == null ? Double.NaN : x;
		this.y = y == null ? Double.NaN : y;
		this.z = z == null ? Double.NaN : z;
		this.t = t == null ? -1 : t;
	}

	public String name() {
		final StringBuilder sb = new StringBuilder("image=" + i);
		if (p >= 0) sb.append("; tile=" + p);
		if (theC >= 0) sb.append("; C=" + theC);
		if (theZ >= 0) sb.append("; Z=" + theZ);
		if (theT >= 0) sb.append("; T=" + theT);
		return sb.toString();
	}

}
