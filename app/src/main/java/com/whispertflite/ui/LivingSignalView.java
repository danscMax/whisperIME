package com.whispertflite.ui;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.TextureView;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

/**
 * Living Signal — a GPU cloud orb shared by the app, provider sheet, IME and onboarding.
 *
 * <p>The surface is rendered with an OpenGL ES 2.0 fragment shader (domain-warped fBm clouds,
 * ported from the MIT-licensed orb-ui "cloud" theme by Alexander Chen) so it keeps full fidelity
 * on every device from minSdk 28 upward. State is carried by palette, flow speed and activity —
 * no blades, rays or spinners. The public API ({@link #setSignalState}, {@link #pushLevel},
 * {@link #setColors}, {@link #setIdle}) is unchanged so callers need no edits.
 */
public class LivingSignalView extends TextureView implements TextureView.SurfaceTextureListener {

    public enum SignalState { READY, LISTENING, PROCESSING, RESULT, ERROR }

    // --- state shared with the render thread (all reads/writes are of primitives / volatiles) ---
    private volatile SignalState state = SignalState.READY;
    private volatile float targetLevel;   // 0..1 smoothed towards on the render thread
    private volatile boolean running;
    // App accent as hue/saturation only, so the cloud recolours to the palette while keeping its
    // airy brightness (blending toward raw palette colours would darken it, esp. in dark theme).
    private volatile float accentHue;
    private volatile boolean accentValid;
    // Orb visual style: 0 = living cloud (default), 1 = plasma/plexus energy ball. Read from the
    // "orbStyle" pref so every orb (main, IME, provider, onboarding) shares the user's choice.
    private volatile int orbStyle;
    private RenderThread thread;

    public LivingSignalView(Context context) {
        super(context);
        init();
    }

    public LivingSignalView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setOpaque(false);                 // transparent orb composited over the glass card
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setSurfaceTextureListener(this);
    }

    // ----- public API (unchanged) -----

    public void setSignalState(SignalState next) {
        if (next != null) state = next;
    }

    public SignalState getSignalState() {
        return state;
    }

    public void pushLevel(float rms) {
        targetLevel = LivingSignalDynamics.normalize(rms);
    }

    public void setIdle() {
        targetLevel = 0f;
        state = SignalState.READY;
    }

    /** Re-read the orb-style pref; if it changed, the render thread recompiles to the new shader so a
     *  choice made in Settings takes effect when the screen resumes, without a restart. */
    public void refreshStyle() {
        int s = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(getContext()).getInt("orbStyle", 0);
        if (s != orbStyle) {
            orbStyle = s;
            RenderThread rt = thread;
            if (rt != null) rt.styleDirty = true;
        }
    }

    /** Recolour the cloud toward the app palette's hue (colorPrimary) so the orb follows the palette. */
    public void setColors(int bright, int soft) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(bright, hsv);
        accentHue = hsv[0];
        accentValid = true;
    }

    // ----- TextureView lifecycle -----

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        orbStyle = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(getContext()).getInt("orbStyle", 0);
        running = true;
        thread = new RenderThread(surface, width, height);
        thread.start();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        if (thread != null) thread.resize(width, height);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        running = false;
        if (thread != null) {
            thread.finish();
            try { thread.join(400); } catch (InterruptedException ignored) { }
            thread = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) { }

    // ================= render thread =================

    private final class RenderThread extends Thread {
        private final SurfaceTexture surface;
        private volatile int width, height;
        private volatile boolean sizeDirty;
        private volatile boolean styleDirty;   // set when the orb style changed → recompile the shader

        RenderThread(SurfaceTexture surface, int w, int h) {
            this.surface = surface;
            this.width = w;
            this.height = h;
        }

        void resize(int w, int h) { width = w; height = h; sizeDirty = true; }
        void finish() { running = false; }

        // EGL
        private EGL10 egl;
        private EGLDisplay display;
        private EGLContext context;
        private EGLSurface eglSurface;
        // GL program
        private int program, uRes, uTime, uAct, uDeep, uUpper, uLower, uMilk;
        // smoothed values
        private float level, flow, act = 0.12f;
        private final float[] tinted = new float[12];   // reused accent-blended palette (no per-frame alloc)
        private final float[] hsv = new float[3];        // reused HSV scratch for hue recolouring

        @Override
        public void run() {
            if (!initEGL()) return;
            initGL();
            // Respect the system "Remove animations" setting: hold one static frame per state instead
            // of the ~80 fps loop — saves battery and honours the a11y/reduced-motion preference.
            boolean reduced;
            try {
                reduced = Settings.Global.getFloat(getContext().getContentResolver(),
                        Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
            } catch (Exception e) {
                reduced = false;
            }
            SignalState lastStatic = null;
            long last = System.nanoTime();
            while (running) {
                if (reduced) {
                    if (state != lastStatic) {
                        drawFrame(0.2f);   // one settled frame so activity reaches the state's target
                        if (!egl.eglSwapBuffers(display, eglSurface)) break;
                        lastStatic = state;
                    }
                    try { Thread.sleep(100); } catch (InterruptedException e) { break; }
                    continue;
                }
                long now = System.nanoTime();
                float dt = Math.min((now - last) / 1e9f, 0.05f);
                last = now;
                drawFrame(dt);
                if (!egl.eglSwapBuffers(display, eglSurface)) break;
                // The calm states (READY/ERROR) drift slowly, so 30 fps looks identical there and is
                // far easier on the battery than running the full ~80 fps whenever the orb is on
                // screen; only the active states need every frame. dt-based stepping keeps the motion
                // speed the same either way.
                boolean active = state == SignalState.LISTENING || state == SignalState.PROCESSING
                        || state == SignalState.RESULT;
                try { Thread.sleep(active ? 12 : 33); } catch (InterruptedException e) { break; }
            }
            releaseEGL();
        }

        private void drawFrame(float dt) {
            if (styleDirty) { GLES20.glDeleteProgram(program); initGL(); styleDirty = false; }
            if (sizeDirty) { GLES20.glViewport(0, 0, width, height); sizeDirty = false; }

            // smooth audio level (fast attack, slow decay) + per-state flow speed / activity
            level = LivingSignalDynamics.step(level, state == SignalState.LISTENING ? targetLevel : 0f);
            float speed, targetAct, tint;
            float[] pal;
            switch (state) {
                case LISTENING:  speed = 0.72f + level * 0.9f; targetAct = 0.28f + level * 0.34f; pal = PAL_CYAN; tint = 0.30f; break;
                case PROCESSING: speed = 0.70f;                targetAct = 0.34f;                 pal = PAL_BLUE; tint = 0.24f; break;
                case RESULT:     speed = 1.40f;                targetAct = 0.60f;                 pal = PAL_CYAN; tint = 0.30f; break;
                case ERROR:      speed = 0.30f;                targetAct = 0.14f;                 pal = PAL_WARM; tint = 0.00f; break;  // stay semantic warm-red
                case READY:
                default:         speed = 0.26f;                targetAct = 0.12f;                 pal = PAL_COOL; tint = 0.32f; break;
            }
            // Take the palette's hue outright, keeping each cloud tone's airy saturation/value, so
            // the orb reads as the chosen colour. A partial blend toward the hue looked like the
            // palette barely mattered on cool seeds and swung through purple on the warm one, since a
            // hue halfway between two colours is a third colour, not a hint of the target. ERROR keeps
            // its semantic warm-red (gate is tint == 0).
            if (accentValid && tint > 0f) {
                for (int k = 0; k < 4; k++) {
                    int col = android.graphics.Color.rgb(
                            Math.round(pal[k * 3] * 255f), Math.round(pal[k * 3 + 1] * 255f), Math.round(pal[k * 3 + 2] * 255f));
                    android.graphics.Color.colorToHSV(col, hsv);
                    hsv[0] = accentHue;   // hue from the palette; keep the cloud's airy S/V
                    int out = android.graphics.Color.HSVToColor(hsv);
                    tinted[k * 3] = android.graphics.Color.red(out) / 255f;
                    tinted[k * 3 + 1] = android.graphics.Color.green(out) / 255f;
                    tinted[k * 3 + 2] = android.graphics.Color.blue(out) / 255f;
                }
                pal = tinted;
            }
            flow += dt * speed;
            act += (targetAct - act) * Math.min(1f, dt * 6f);

            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);
            GLES20.glUniform2f(uRes, width, height);
            GLES20.glUniform1f(uTime, flow);
            GLES20.glUniform1f(uAct, act);
            GLES20.glUniform3f(uDeep, pal[0], pal[1], pal[2]);
            GLES20.glUniform3f(uUpper, pal[3], pal[4], pal[5]);
            GLES20.glUniform3f(uLower, pal[6], pal[7], pal[8]);
            GLES20.glUniform3f(uMilk, pal[9], pal[10], pal[11]);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
        }

        // ---- GL setup ----

        private void initGL() {
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glEnable(GLES20.GL_BLEND);
            // premultiplied-alpha blending (matches setOpaque(false) compositing)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);

            program = link(VERT, orbStyle == 1 ? FRAG_PLASMA : FRAG);
            GLES20.glUseProgram(program);
            uRes = GLES20.glGetUniformLocation(program, "u_resolution");
            uTime = GLES20.glGetUniformLocation(program, "u_time");
            uAct = GLES20.glGetUniformLocation(program, "u_activity");
            uDeep = GLES20.glGetUniformLocation(program, "u_deep");
            uUpper = GLES20.glGetUniformLocation(program, "u_upper");
            uLower = GLES20.glGetUniformLocation(program, "u_lower");
            uMilk = GLES20.glGetUniformLocation(program, "u_milk");

            FloatBuffer quad = ByteBuffer.allocateDirect(12 * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(new float[]{-1, -1, 1, -1, -1, 1, -1, 1, 1, -1, 1, 1}).position(0);
            int a = GLES20.glGetAttribLocation(program, "a_position");
            GLES20.glEnableVertexAttribArray(a);
            GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 0, quad);
            GLES20.glViewport(0, 0, width, height);
        }

        private int link(String vs, String fs) {
            int v = compile(GLES20.GL_VERTEX_SHADER, vs);
            int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, v);
            GLES20.glAttachShader(p, f);
            GLES20.glLinkProgram(p);
            // Check the status: a driver that rejects the program otherwise leaves a blank orb with
            // no clue why. Log the info log so a field GPU issue is at least diagnosable.
            int[] linked = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linked, 0);
            if (linked[0] == 0) {
                Log.e("LivingSignalView", "orb program link failed: " + GLES20.glGetProgramInfoLog(p));
            }
            return p;
        }

        private int compile(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) {
                Log.e("LivingSignalView", "orb shader compile failed (type " + type + "): "
                        + GLES20.glGetShaderInfoLog(s));
            }
            return s;
        }

        // ---- EGL setup ----

        private boolean initEGL() {
            egl = (EGL10) EGLContext.getEGL();
            display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
            egl.eglInitialize(display, new int[2]);
            int[] cfg = {
                    EGL10.EGL_RENDERABLE_TYPE, 4 /* EGL_OPENGL_ES2_BIT */,
                    EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] num = new int[1];
            if (!egl.eglChooseConfig(display, cfg, configs, 1, num) || num[0] == 0) return false;
            EGLConfig config = configs[0];
            context = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT,
                    new int[]{0x3098 /* EGL_CONTEXT_CLIENT_VERSION */, 2, EGL10.EGL_NONE});
            eglSurface = egl.eglCreateWindowSurface(display, config, surface, null);
            if (eglSurface == null || eglSurface == EGL10.EGL_NO_SURFACE) return false;
            return egl.eglMakeCurrent(display, eglSurface, eglSurface, context);
        }

        private void releaseEGL() {
            if (egl == null || display == null) return;
            egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
            if (eglSurface != null) egl.eglDestroySurface(display, eglSurface);
            if (context != null) egl.eglDestroyContext(display, context);
            // Deliberately NOT eglTerminate(display): EGL_DEFAULT_DISPLAY is process-global and shared by
            // every orb (onboarding, main, provider, IME). Terminating it here tore down the display a
            // *concurrently starting* orb had just initialised — e.g. onboarding finishes a download and
            // launches MainActivity, whose orb inits EGL while this (onboarding) orb tears down and
            // terminates the shared display — leaving the new orb blank or frozen. Destroying only this
            // instance's surface+context is correct; the display stays initialised for the others.
        }
    }

    // palettes: deep, upper, lower, milk (RGB 0..1, 12 floats each)
    private static final float[] PAL_COOL = {
            0.36f,0.39f,0.985f, 0.48f,0.56f,0.985f, 0.72f,0.78f,0.975f, 0.89f,0.92f,0.995f };
    private static final float[] PAL_CYAN = {
            0.24f,0.56f,0.98f, 0.40f,0.72f,0.98f, 0.66f,0.87f,0.98f, 0.88f,0.97f,1.0f };
    private static final float[] PAL_BLUE = {
            0.30f,0.34f,0.99f, 0.40f,0.48f,0.99f, 0.60f,0.68f,0.985f, 0.86f,0.90f,1.0f };
    private static final float[] PAL_WARM = {
            0.95f,0.30f,0.28f, 0.98f,0.47f,0.38f, 0.99f,0.72f,0.63f, 1.0f,0.90f,0.84f };

    private static final String VERT =
            "attribute vec2 a_position;\n" +
            "void main(){ gl_Position = vec4(a_position, 0.0, 1.0); }\n";

    // orb-ui cloud fragment shader (MIT, Alexander Chen) with palette exposed as uniforms,
    // premultiplied-alpha output for TextureView compositing.
    private static final String FRAG =
            "precision highp float;\n" +
            "uniform vec2 u_resolution; uniform float u_time, u_activity;\n" +
            "uniform vec3 u_deep, u_upper, u_lower, u_milk;\n" +
            "float hash(vec2 p){ p=fract(p*vec2(123.34,456.21)); p+=dot(p,p+45.32); return fract(p.x*p.y); }\n" +
            "float noise(vec2 p){ vec2 i=floor(p),f=fract(p); vec2 u=f*f*(3.0-2.0*f);\n" +
            "  return mix(mix(hash(i),hash(i+vec2(1.0,0.0)),u.x),mix(hash(i+vec2(0.0,1.0)),hash(i+vec2(1.0,1.0)),u.x),u.y); }\n" +
            "float fbm(vec2 p){ float v=0.0,a=0.52; mat2 r=mat2(0.80,0.60,-0.60,0.80);\n" +
            "  for(int o=0;o<5;o++){ v+=a*noise(p); p=r*p*1.92+vec2(9.7,4.3); a*=0.5; } return v; }\n" +
            "void main(){\n" +
            "  vec2 uv=gl_FragCoord.xy/u_resolution; vec2 c=uv-0.5; float rad=length(c);\n" +
            "  float edge=1.0-smoothstep(0.488,0.5,rad); if(edge<=0.0) discard;\n" +
            "  vec2 p=c*2.0; float t=u_time;\n" +
            "  vec2 warp=vec2(fbm(p*1.02+vec2(t*0.34,-t*0.24)), fbm(p*1.08+vec2(-t*0.27,t*0.32)+vec2(6.7,2.9)));\n" +
            "  vec2 curl=vec2(sin(p.y*2.4+t*0.68+warp.y*3.2), cos(p.x*2.1-t*0.61+warp.x*3.0));\n" +
            "  vec2 w=p+(warp-0.5)*(1.18+u_activity*0.38)+curl*(0.035+u_activity*0.07);\n" +
            "  float broad=fbm(w*0.92+vec2(t*0.14,-t*0.18));\n" +
            "  float folded=fbm(w*1.66+vec2(-t*0.23,t*0.19)+5.2);\n" +
            "  float field=mix(broad,folded,0.3+u_activity*0.14);\n" +
            "  float hz=0.46+0.08*sin((uv.x+warp.x*0.2)*5.4+t*0.42)+0.16*(broad-0.5);\n" +
            "  float up=smoothstep(hz-0.12,hz+0.08,uv.y);\n" +
            "  float band=exp(-pow((uv.y-hz)*(5.2+u_activity*0.8),2.0));\n" +
            "  float cl=smoothstep(0.24,0.79,field);\n" +
            "  vec3 col=mix(u_lower,u_upper,up);\n" +
            "  col=mix(col,u_deep,up*(0.14+smoothstep(0.42,0.78,folded)*0.5));\n" +
            "  col=mix(col,u_milk,clamp(band*(0.42+cl*0.62),0.0,0.88));\n" +
            "  col=mix(col,u_milk,(1.0-up)*smoothstep(0.58,0.9,broad)*0.18);\n" +
            "  col+=(noise(gl_FragCoord.xy*0.64)-0.5)/255.0;\n" +
            "  gl_FragColor=vec4(col*edge, edge);\n" +   // premultiplied alpha
            "}\n";

    // Plasma / plexus energy-ball style (selectable). Same uniforms as FRAG: u_milk = brightest
    // (core, nodes, corona), u_upper = body blue, u_lower = mid, u_deep = rim. u_activity energises
    // the network. A drifting grid of nodes is linked to its neighbours (a "plexus"), over a blue
    // sphere with a bright stretched core and a glowing rim corona that spills just past the disk.
    private static final String FRAG_PLASMA =
            "precision highp float;\n" +
            "uniform vec2 u_resolution; uniform float u_time, u_activity;\n" +
            "uniform vec3 u_deep, u_upper, u_lower, u_milk;\n" +
            "float hash(vec2 p){ p=fract(p*vec2(123.34,456.21)); p+=dot(p,p+45.32); return fract(p.x*p.y); }\n" +
            "vec2 hash2(vec2 p){ float n=hash(p); return vec2(n, hash(p+n+17.1)); }\n" +
            "float seg(vec2 p, vec2 a, vec2 b){ vec2 pa=p-a, ba=b-a; float h=clamp(dot(pa,ba)/max(dot(ba,ba),1e-4),0.0,1.0); return length(pa-ba*h); }\n" +
            "void main(){\n" +
            "  vec2 uv=gl_FragCoord.xy/u_resolution; vec2 c=uv-0.5;\n" +
            "  float aspect=u_resolution.x/max(u_resolution.y,1.0);\n" +
            "  vec2 cc=vec2(c.x*aspect, c.y); float rad=length(cc);\n" +
            "  if(rad>0.52) discard;\n" +
            "  float t=u_time; float energy=0.6+u_activity*1.5;\n" +
            // fixed electric-blue palette (independent of the app palette, matching the plasma reference)
            "  vec3 cDeep=vec3(0.05,0.15,0.60), cBody=vec3(0.22,0.42,0.98), cBright=vec3(0.82,0.91,1.0);\n" +
            "  float body=1.0-smoothstep(0.0,0.42,rad);\n" +
            "  vec3 col=mix(cDeep,cBody,body);\n" +
            // bright horizontally-stretched core (lens-flare-like)
            "  float core=exp(-pow(length(vec2(cc.x*0.85,cc.y*2.0))*4.2,2.0));\n" +
            "  col=mix(col,cBright,clamp(core*1.3,0.0,1.0));\n" +
            "  float disk=1.0-smoothstep(0.40,0.44,rad);\n" +
            // denser plexus: 10-cell grid, thinner brighter links + nodes
            "  float grid=10.0; vec2 gp=cc*grid; vec2 gi=floor(gp);\n" +
            "  vec2 p0=gi+0.5+0.4*sin(t*0.5+hash2(gi)*6.2831);\n" +
            "  float net=0.0, dots=0.0;\n" +
            "  for(int y=-1;y<=1;y++){ for(int x=-1;x<=1;x++){\n" +
            "    vec2 gn=gi+vec2(float(x),float(y));\n" +
            "    vec2 pn=gn+0.5+0.4*sin(t*0.5+hash2(gn)*6.2831);\n" +
            "    float d=length(gp-pn); dots+=smoothstep(0.10,0.0,d);\n" +
            "    if(x!=0||y!=0){ float ls=seg(gp,p0,pn);\n" +
            "      net+=smoothstep(0.032,0.0,ls)*smoothstep(1.5,0.5,length(p0-pn)); }\n" +
            "  }}\n" +
            "  float netB=(net*0.95+dots*1.35)*(0.4+body*0.9)*energy;\n" +
            "  col=mix(col,cBright,clamp(netB,0.0,0.95)); col*=disk;\n" +
            // diffuse, slightly wispy corona around the (smaller) sphere, spilling out to the view edge
            "  float ang=atan(cc.y,cc.x);\n" +
            "  float wob=0.55+0.45*sin(ang*7.0+t*0.55)*sin(ang*3.0-t*0.4);\n" +
            "  float ring=exp(-pow((rad-0.43)*34.0,2.0));\n" +
            "  float halo=smoothstep(0.52,0.42,rad)*wob;\n" +
            "  vec3 rimCol=vec3(0.72,0.87,1.0);\n" +
            "  col+=rimCol*(ring*1.25+halo*0.75*(1.0-disk))*(0.9+u_activity*0.6);\n" +
            "  float alpha=clamp(disk+ring*0.95+halo*0.55*(1.0-disk),0.0,1.0);\n" +
            "  col+=(hash(gl_FragCoord.xy*0.7)-0.5)/255.0;\n" +
            "  gl_FragColor=vec4(col*alpha, alpha);\n" +
            "}\n";
}
