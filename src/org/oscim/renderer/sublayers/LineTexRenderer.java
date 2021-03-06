/*
 * Copyright 2013 Hannes Janetzek
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.oscim.renderer.sublayers;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.oscim.core.MapPosition;
import org.oscim.renderer.GLRenderer;
import org.oscim.renderer.GLState;
import org.oscim.renderer.GLRenderer.Matrices;
import org.oscim.theme.renderinstruction.Line;
import org.oscim.utils.GlUtils;

import android.opengl.GLES20;
import android.util.Log;

public class LineTexRenderer {
	private final static String TAG = LineTexRenderer.class.getName();

	// factor to normalize extrusion vector and scale to coord scale
	private final static float COORD_SCALE_BY_DIR_SCALE =
			GLRenderer.COORD_SCALE / LineLayer.DIR_SCALE;

	private static int shader;
	private static int hVertexPosition0;
	private static int hVertexPosition1;
	private static int hVertexLength0;
	private static int hVertexLength1;
	private static int hVertexFlip;
	private static int hMatrix;
	private static int hTexColor;
	private static int hBgColor;
	private static int hScale;
	private static int hWidth;
	private static int hPatternScale;
	private static int hPatternWidth;

	private static int mVertexFlipID;

	public static void init() {
		shader = GlUtils.createProgram(vertexShader, fragmentShader);
		if (shader == 0) {
			Log.e(TAG, "Could not create  program.");
			return;
		}

		hMatrix = GLES20.glGetUniformLocation(shader, "u_mvp");
		hTexColor = GLES20.glGetUniformLocation(shader, "u_color");
		hBgColor = GLES20.glGetUniformLocation(shader, "u_bgcolor");
		hScale = GLES20.glGetUniformLocation(shader, "u_scale");
		hWidth = GLES20.glGetUniformLocation(shader, "u_width");
		hPatternScale = GLES20.glGetUniformLocation(shader, "u_pscale");
		hPatternWidth = GLES20.glGetUniformLocation(shader, "u_pwidth");

		hVertexPosition0 = GLES20.glGetAttribLocation(shader, "a_pos0");
		hVertexPosition1 = GLES20.glGetAttribLocation(shader, "a_pos1");
		hVertexLength0 = GLES20.glGetAttribLocation(shader, "a_len0");
		hVertexLength1 = GLES20.glGetAttribLocation(shader, "a_len1");
		hVertexFlip = GLES20.glGetAttribLocation(shader, "a_flip");

		int[] vboIds = new int[1];
		GLES20.glGenBuffers(1, vboIds, 0);
		mVertexFlipID = vboIds[0];

		// 0, 1, 0, 1, 0, ...
		byte[] flip = new byte[GLRenderer.maxQuads * 4];
		for (int i = 0; i < flip.length; i++)
			flip[i] = (byte) (i % 2);

		ByteBuffer buf = ByteBuffer.allocateDirect(flip.length)
				.order(ByteOrder.nativeOrder());

		buf.put(flip);
		buf.flip();
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mVertexFlipID);
		GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, flip.length, buf,
				GLES20.GL_STATIC_DRAW);
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);


//		mTexID = new int[10];
//		byte[] stipple = new byte[2];
//		stipple[0] = 32;
//		stipple[1] = 32;
//		mTexID[0] = GlUtils.loadStippleTexture(stipple);
	}

	private final static int STRIDE = 12;
	private final static int LEN_OFFSET = 8;

	public static Layer draw(Layers layers, Layer curLayer,
			MapPosition pos, Matrices m, float div) {

		GLState.blend(true);
		GLState.useProgram(shader);

		GLState.enableVertexArrays(-1, -1);

		GLES20.glEnableVertexAttribArray(hVertexPosition0);
		GLES20.glEnableVertexAttribArray(hVertexPosition1);
		GLES20.glEnableVertexAttribArray(hVertexLength0);
		GLES20.glEnableVertexAttribArray(hVertexLength1);
		GLES20.glEnableVertexAttribArray(hVertexFlip);

		m.mvp.setAsUniform(hMatrix);

		int maxIndices = GLRenderer.maxQuads * 6;
		GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER,
				GLRenderer.mQuadIndicesID);

		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mVertexFlipID);
		GLES20.glVertexAttribPointer(hVertexFlip, 1,
				GLES20.GL_BYTE, false, 0, 0);

		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, layers.vbo.id);

		float scale = (float)pos.getZoomScale();

		float s = scale / div;

		//GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexID[0]);

		Layer l = curLayer;
		for (;l != null && l.type == Layer.TEXLINE; l = l.next) {
			LineTexLayer ll = (LineTexLayer) l;
			Line line = ll.line;

			GlUtils.setColor(hTexColor, line.stippleColor, 1);
			GlUtils.setColor(hBgColor, line.color, 1);

			float pScale = (int) (s+0.5f);
			if (pScale < 1)
				pScale = 1;

			GLES20.glUniform1f(hPatternScale, (GLRenderer.COORD_SCALE * line.stipple) / pScale);
			GLES20.glUniform1f(hPatternWidth, line.stippleWidth);

			GLES20.glUniform1f(hScale, scale);
			// keep line width fixed
			GLES20.glUniform1f(hWidth, ll.width / s * COORD_SCALE_BY_DIR_SCALE);

			// add offset vertex
			int vOffset = -STRIDE;

			// first pass
			int allIndices = (ll.evenQuads * 6);
			for (int i = 0; i < allIndices; i += maxIndices) {
				int numIndices = allIndices - i;
				if (numIndices > maxIndices)
					numIndices = maxIndices;

				// i / 6 * (24 shorts per block * 2 short bytes)
				int add = (l.offset + i * 8) + vOffset;

				GLES20.glVertexAttribPointer(hVertexPosition0,
						4, GLES20.GL_SHORT, false, STRIDE,
						add + STRIDE);

				GLES20.glVertexAttribPointer(hVertexLength0,
						2, GLES20.GL_SHORT, false, STRIDE,
						add + STRIDE + LEN_OFFSET);

				GLES20.glVertexAttribPointer(hVertexPosition1,
						4, GLES20.GL_SHORT, false, STRIDE,
						add);

				GLES20.glVertexAttribPointer(hVertexLength1,
						2, GLES20.GL_SHORT, false, STRIDE,
						add + LEN_OFFSET);

				GLES20.glDrawElements(GLES20.GL_TRIANGLES, numIndices,
						GLES20.GL_UNSIGNED_SHORT, 0);
			}

			// second pass
			allIndices = (ll.oddQuads * 6);
			for (int i = 0; i < allIndices; i += maxIndices) {
				int numIndices = allIndices - i;
				if (numIndices > maxIndices)
					numIndices = maxIndices;
				// i / 6 * (24 shorts per block * 2 short bytes)
				int add = (l.offset + i * 8) + vOffset;

				GLES20.glVertexAttribPointer(hVertexPosition0,
						4, GLES20.GL_SHORT, false, STRIDE,
						add + 2 * STRIDE);

				GLES20.glVertexAttribPointer(hVertexLength0,
						2, GLES20.GL_SHORT, false, STRIDE,
						add + 2 * STRIDE + LEN_OFFSET);

				GLES20.glVertexAttribPointer(hVertexPosition1,
						4, GLES20.GL_SHORT, false, STRIDE,
						add + STRIDE);

				GLES20.glVertexAttribPointer(hVertexLength1,
						2, GLES20.GL_SHORT, false, STRIDE,
						add + STRIDE + LEN_OFFSET);

				GLES20.glDrawElements(GLES20.GL_TRIANGLES, numIndices,
						GLES20.GL_UNSIGNED_SHORT, 0);
			}
			//GlUtils.checkGlError(TAG);
		}

		GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);

		GLES20.glDisableVertexAttribArray(hVertexPosition0);
		GLES20.glDisableVertexAttribArray(hVertexPosition1);
		GLES20.glDisableVertexAttribArray(hVertexLength0);
		GLES20.glDisableVertexAttribArray(hVertexLength1);
		GLES20.glDisableVertexAttribArray(hVertexFlip);

		//GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

		return l;
	}

	final static String vertexShader = ""
			+ "precision mediump float;"
			+ "uniform mat4 u_mvp;"
			+ "uniform vec4 u_color;"
			+ "uniform float u_pscale;"
			+ "uniform float u_width;"
			+ "attribute vec4 a_pos0;"
			+ "attribute vec4 a_pos1;"
			+ "attribute vec2 a_len0;"
			+ "attribute vec2 a_len1;"
			+ "attribute float a_flip;"
			+ "varying vec2 v_st;"
			+ "void main() {"
			+ "  vec4 pos;"
			+ "  if (a_flip == 0.0){"
			+ "    vec2 dir = u_width * a_pos0.zw;"
			+ "    pos = vec4(a_pos0.xy + dir, 0.0, 1.0);"
			+ "    v_st = vec2(a_len0.x / u_pscale, 1.0);"
			+ "  } else {"
			+ "    vec2 dir = u_width * a_pos1.zw ;"
			+ "     pos = vec4(a_pos1.xy - dir, 0.0, 1.0);"
			+ "    v_st = vec2(a_len1.x / u_pscale, -1.0);"
			+ "  }"
			+ "  gl_Position = u_mvp * pos;"
			+ "}";

	//*
	final static String fragmentShader = ""
			+ "#extension GL_OES_standard_derivatives : enable\n"
			+ " precision mediump float;"
			+ " uniform vec4 u_color;"
			+ " uniform vec4 u_bgcolor;"
			+ " uniform float u_pwidth;"
			+ " varying vec2 v_st;"
			+ " void main() {"
			//   distance on perpendicular to the line
			+ "  float dist = abs(v_st.t);"
			+ "  float fuzz = fwidth(v_st.t);"
			+ "  float fuzz_p = fwidth(v_st.s);"
			+ "  float line_w = smoothstep(0.0, fuzz, 1.0 - dist);"
			+ "  float stipple_w = smoothstep(0.0, fuzz, u_pwidth - dist);"
			// triangle waveform in the range 0..1 for regular pattern
			+ "  float phase = abs(mod(v_st.s, 2.0) - 1.0);"
			// interpolate between on/off phase, 0.5 = equal phase length
			+ "  float stipple_p = smoothstep(0.5 - fuzz_p, 0.5 + fuzz_p, phase);"
			+ "  gl_FragColor = line_w * mix(u_bgcolor, u_color, min(stipple_w, stipple_p));"
			+ " } ";  //*/

	/*
	final static String fragmentShader = ""
			+ "#extension GL_OES_standard_derivatives : enable\n"
			+ " precision mediump float;"
			+ " uniform sampler2D tex;"
			+ " uniform float u_scale;"
			+ " uniform vec4 u_color;"
			+ " uniform vec4 u_bgcolor;"
			+ " varying vec2 v_st;"
			+ " void main() {"
			+ "  float len = texture2D(tex, v_st).a;"
			+ "  float tex_w = abs(v_st.t);"
			+ "  vec2 st_width = fwidth(v_st);"
			+ "  float fuzz = max(st_width.s, st_width.t);"
			//+ "  float fuzz = fwidth(v_st.t);"
			//+ "  float line_w    = 1.0 - smoothstep(1.0 - fuzz, 1.0, tex_w);"
			//+ "  float stipple_w = 1.0 - smoothstep(0.7 - fuzz, 0.7, tex_w);"
			+ "  float stipple_p = 1.0 - smoothstep(1.0 - fuzz, 1.0, length(vec2(len*u_scale, v_st.t)));"
			+ "  gl_FragColor =  u_bgcolor * stipple_p;"
			//			+ "  gl_FragColor = line_w * mix(u_bgcolor, u_color, min(stipple_w, stipple_p));"
			+ "}"; //*/
	/*
	final static String fragmentShader = ""
			+ "#extension GL_OES_standard_derivatives : enable\n"
			+ " precision mediump float;"
			+ " uniform sampler2D tex;"
			+ " uniform vec4 u_color;"
			+ " uniform vec4 u_bgcolor;"
			+ " uniform float u_pwidth;"
			+ " varying vec2 v_st;"
			+ " void main() {"
			+ "  float dist = texture2D(tex, v_st).a;"
			+ "  float tex_w = abs(v_st.t);"
			+ "  vec2 st_width = fwidth(v_st);"
			+ "  float fuzz = max(st_width.s, st_width.t);"
			+ "  float line_w    = (1.0 - smoothstep(1.0 - fuzz, 1.0, tex_w));"
			+ "  float stipple_w = (1.0 - smoothstep(u_pwidth - fuzz, u_pwidth, tex_w));"
			+ "  float stipple_p = smoothstep(0.495, 0.505, dist);"
			+ "  gl_FragColor = line_w * mix(u_bgcolor, u_color, min(stipple_w, stipple_p));"
			+ " } ";	//*/

}
