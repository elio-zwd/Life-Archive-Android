package com.elio.lifearchive.core.live2d;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import com.live2d.sdk.cubism.framework.CubismFramework;
import com.live2d.sdk.cubism.framework.CubismModelSettingJson;
import com.live2d.sdk.cubism.framework.effect.CubismEyeBlink;
import com.live2d.sdk.cubism.framework.math.CubismMatrix44;
import com.live2d.sdk.cubism.framework.model.CubismUserModel;
import com.live2d.sdk.cubism.framework.motion.CubismMotion;
import com.live2d.sdk.cubism.framework.rendering.android.CubismRendererAndroid;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** 仅在共享 EGL 线程调用。模型实例与纹理归各窗口，不保留 Activity。 */
final class NativeCubismModel extends CubismUserModel {
    private static final String ROOT = "live2d/Hiyori/";
    private final List<CubismMotion> idle = new ArrayList<>();
    private final List<Integer> textures = new ArrayList<>();
    private CubismMotion tap;
    private float elapsed;
    private String mood = "CALM";
    private int nextIdle;
    private int width;
    private int height;

    static NativeCubismModel create(AssetManager assets, int width, int height) throws IOException {
        NativeCubismModel result = new NativeCubismModel();
        try { result.load(assets, width, height); return result; }
        catch (IOException | RuntimeException | LinkageError error) { result.close(); throw error; }
    }

    private static byte[] read(AssetManager assets, String path) throws IOException {
        try (InputStream input = assets.open(path); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private void load(AssetManager assets, int width, int height) throws IOException {
        this.width = width;
        this.height = height;
        CubismModelSettingJson setting = new CubismModelSettingJson(read(assets, ROOT + "Hiyori.model3.json"));
        loadModel(read(assets, ROOT + setting.getModelFileName()), true);
        if (model == null) throw new IOException("模型一致性检查未通过");
        if (!setting.getPhysicsFileName().isEmpty()) loadPhysics(read(assets, ROOT + setting.getPhysicsFileName()));
        if (!setting.getPoseFileName().isEmpty()) loadPose(read(assets, ROOT + setting.getPoseFileName()));
        eyeBlink = CubismEyeBlink.create(setting);
        for (int groupIndex = 0; groupIndex < setting.getMotionGroupCount(); groupIndex++) {
            String group = setting.getMotionGroupName(groupIndex);
            for (int i = 0; i < setting.getMotionCount(group); i++) {
                CubismMotion motion = loadMotion(read(assets, ROOT + setting.getMotionFileName(group, i)));
                if (motion == null) throw new IOException("动作载入失败");
                motion.setFadeInTime(0.5f);
                motion.setFadeOutTime(0.5f);
                if (group.equals("Idle")) idle.add(motion);
                else if (group.equals("TapBody")) tap = motion;
            }
        }
        setupRenderer(CubismRendererAndroid.create(width, height));
        CubismRendererAndroid renderer = getRenderer();
        renderer.isPremultipliedAlpha(true);
        for (int i = 0; i < setting.getTextureCount(); i++) {
            Bitmap bitmap;
            try (InputStream input = assets.open(ROOT + setting.getTextureFileName(i))) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inScaled = false;
                bitmap = BitmapFactory.decodeStream(input, null, options);
            }
            if (bitmap == null) throw new IOException("纹理载入失败");
            int[] texture = new int[1];
            GLES20.glGenTextures(1, texture, 0);
            textures.add(texture[0]);
            try {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0]);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
                renderer.bindTexture(i, texture[0]);
            } finally { bitmap.recycle(); }
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    void cue(String mood) {
        this.mood = mood;
        if (mood.equals("HAPPY") && tap != null) motionManager.startMotionPriority(tap, 3);
    }

    void touch(float x, float y, boolean tapped) {
        setDragging(x, y);
        if (tapped && tap != null) motionManager.startMotionPriority(tap, 3);
    }

    void draw(float delta) {
        elapsed += delta;
        model.loadParameters();
        if (motionManager.isFinished() && !idle.isEmpty()) {
            motionManager.startMotionPriority(idle.get(nextIdle++ % idle.size()), 1);
        }
        boolean motionUpdated = motionManager.updateMotion(model, delta);
        model.saveParameters();
        if (!motionUpdated && eyeBlink != null) eyeBlink.updateParameters(model, delta);
        dragManager.update(delta);
        add("ParamAngleX", dragManager.getX() * 20f);
        add("ParamAngleY", dragManager.getY() * 15f);
        add("ParamEyeBallX", dragManager.getX());
        add("ParamEyeBallY", dragManager.getY());
        add("ParamBreath", (float) (Math.sin(elapsed * 2.0) + 1) * 0.2f);
        if (mood.equals("HAPPY")) { set("ParamMouthForm", 0.8f); set("ParamEyeLSmile", 0.7f); set("ParamEyeRSmile", 0.7f); }
        else if (mood.equals("THINKING")) add("ParamAngleZ", 8f);
        else if (mood.equals("CARING")) { set("ParamMouthForm", 0.4f); add("ParamAngleZ", -5f); }
        if (physics != null) physics.evaluate(model, delta);
        if (pose != null) pose.updateParameters(model, delta);
        model.update();
        CubismMatrix44 projection = CubismMatrix44.create();
        float aspect = (float) width / height;
        if (model.getCanvasHeight() / model.getCanvasWidth() < 1f / aspect) {
            modelMatrix.setWidth(2f); projection.scale(1f, aspect);
        } else { modelMatrix.setHeight(2f); projection.scale(1f / aspect, 1f); }
        projection.multiplyByMatrix(modelMatrix);
        CubismRendererAndroid renderer = getRenderer();
        renderer.setMvpMatrix(projection);
        renderer.drawModel();
    }

    private void add(String name, float value) { model.addParameterValue(CubismFramework.getIdManager().getId(name), value); }
    private void set(String name, float value) { model.setParameterValue(CubismFramework.getIdManager().getId(name), value); }

    void resize(int width, int height) { this.width = width; this.height = height; setRenderTargetSize(width, height); }
    void close() {
        motionManager.stopAllMotions();
        delete();
        for (int texture : textures) GLES20.glDeleteTextures(1, new int[]{texture}, 0);
        textures.clear(); idle.clear(); tap = null;
    }
}
