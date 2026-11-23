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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** 仅在共享 EGL 线程调用。模型实例与纹理归各窗口，不保留 Activity。 */
final class NativeCubismModel extends CubismUserModel {
    private interface ModelReader {
        byte[] read(String relativePath) throws IOException;
        Bitmap decodeBitmap(String relativePath) throws IOException;
    }

    private final List<CubismMotion> idle = new ArrayList<>();
    private final List<CubismMotion> tapMotions = new ArrayList<>();
    private final List<Integer> textures = new ArrayList<>();
    private float compactScale = 0.74f;
    private float elapsed;
    private String mood = "CALM";
    private int nextIdle;
    private int nextTap;
    private int width;
    private int height;
    private String reaction = "";
    private float reactionUntil;
    private float lastBodyTap = -10f;
    private boolean compactFraming;

    static NativeCubismModel create(AssetManager assets, int width, int height) throws IOException {
        return create(Live2DModelCatalog.INSTANCE.resolve(null), assets, width, height);
    }

    static NativeCubismModel create(Live2DModelSpec spec, AssetManager assets, int width, int height) throws IOException {
        NativeCubismModel result = new NativeCubismModel();
        try {
            result.load(spec, assets, width, height);
            return result;
        } catch (IOException | RuntimeException | LinkageError error) {
            result.close();
            throw error;
        }
    }

    private static byte[] readStream(InputStream input) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static ModelReader readerFor(Live2DModelSource source, AssetManager assets) throws IOException {
        if (source instanceof Live2DModelSource.Asset) {
            final String root = ((Live2DModelSource.Asset) source).getNormalizedDirectory() + "/";
            return new ModelReader() {
                @Override public byte[] read(String relativePath) throws IOException {
                    return readStream(assets.open(root + relativePath));
                }
                @Override public Bitmap decodeBitmap(String relativePath) throws IOException {
                    try (InputStream input = assets.open(root + relativePath)) {
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inScaled = false;
                        return BitmapFactory.decodeStream(input, null, options);
                    }
                }
            };
        }
        if (source instanceof Live2DModelSource.Directory) {
            final File root = new File(((Live2DModelSource.Directory) source).getDirectoryPath());
            return new ModelReader() {
                @Override public byte[] read(String relativePath) throws IOException {
                    return readStream(new FileInputStream(new File(root, relativePath)));
                }
                @Override public Bitmap decodeBitmap(String relativePath) throws IOException {
                    try (InputStream input = new FileInputStream(new File(root, relativePath))) {
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inScaled = false;
                        return BitmapFactory.decodeStream(input, null, options);
                    }
                }
            };
        }
        throw new IOException("不支持的模型来源：" + source);
    }

    private void load(Live2DModelSpec spec, AssetManager assets, int width, int height) throws IOException {
        this.width = width;
        this.height = height;
        this.compactScale = spec.getCompactScale();
        ModelReader reader = readerFor(spec.getSource(), assets);
        CubismModelSettingJson setting = new CubismModelSettingJson(reader.read(spec.getSource().getModelJson()));
        loadModel(reader.read(setting.getModelFileName()), true);
        if (model == null) throw new IOException("模型一致性检查未通过：" + spec.getId());
        if (!setting.getPhysicsFileName().isEmpty()) loadPhysics(reader.read(setting.getPhysicsFileName()));
        if (!setting.getPoseFileName().isEmpty()) loadPose(reader.read(setting.getPoseFileName()));
        eyeBlink = CubismEyeBlink.create(setting);
        for (int groupIndex = 0; groupIndex < setting.getMotionGroupCount(); groupIndex++) {
            String group = setting.getMotionGroupName(groupIndex);
            for (int i = 0; i < setting.getMotionCount(group); i++) {
                CubismMotion motion = loadMotion(reader.read(setting.getMotionFileName(group, i)));
                if (motion == null) throw new IOException("动作载入失败：" + spec.getId());
                motion.setFadeInTime(0.5f);
                motion.setFadeOutTime(0.5f);
                if (group.equalsIgnoreCase("Idle")) idle.add(motion);
                else tapMotions.add(motion);
            }
        }
        setupRenderer(CubismRendererAndroid.create(width, height));
        CubismRendererAndroid renderer = getRenderer();
        renderer.isPremultipliedAlpha(true);
        for (int i = 0; i < setting.getTextureCount(); i++) {
            Bitmap bitmap = reader.decodeBitmap(setting.getTextureFileName(i));
            if (bitmap == null) throw new IOException("纹理载入失败：" + spec.getId());
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
                // SDK 绘制时会改用 mipmap 过滤；缺少完整层级会采样成黑色剪影。
                GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
                renderer.bindTexture(i, texture[0]);
            } finally {
                bitmap.recycle();
            }
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    private void triggerTapMotion() {
        if (!tapMotions.isEmpty()) {
            motionManager.startMotionPriority(tapMotions.get(nextTap++ % tapMotions.size()), 3);
        } else if (!idle.isEmpty()) {
            // 部分轻量模型（如 Mark）仅定义 Idle 动作组，点击时轮播下一条待机动作。
            motionManager.startMotionPriority(idle.get(nextIdle++ % idle.size()), 2);
        }
    }

    void cue(String mood) {
        this.mood = mood;
        if (mood.equals("HAPPY")) triggerTapMotion();
    }

    void touch(float x, float y, boolean tapped) {
        setDragging(x, y);
        if (tapped) triggerTapMotion();
    }

    void react(String kind) {
        reaction = kind;
        reactionUntil = elapsed + 1.6f;
        if (kind.equals("BODY") && elapsed - lastBodyTap > 2f) {
            lastBodyTap = elapsed;
            triggerTapMotion();
        }
    }

    void compactFraming(boolean enabled) { compactFraming = enabled; }

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
        addDual("ParamAngleX", "PARAM_ANGLE_X", dragManager.getX() * 20f);
        addDual("ParamAngleY", "PARAM_ANGLE_Y", dragManager.getY() * 15f);
        addDual("ParamEyeBallX", "PARAM_EYE_BALL_X", dragManager.getX());
        addDual("ParamEyeBallY", "PARAM_EYE_BALL_Y", dragManager.getY());
        addDual("ParamBreath", "PARAM_BREATH", (float) (Math.sin(elapsed * 2.0) + 1) * 0.2f);
        if (mood.equals("HAPPY")) {
            setDual("ParamMouthForm", "PARAM_MOUTH_FORM", 0.8f);
            setDual("ParamEyeLSmile", "PARAM_EYE_L_SMILE", 0.7f);
            setDual("ParamEyeRSmile", "PARAM_EYE_R_SMILE", 0.7f);
        } else if (mood.equals("THINKING")) {
            addDual("ParamAngleZ", "PARAM_ANGLE_Z", 8f);
        } else if (mood.equals("CARING")) {
            setDual("ParamMouthForm", "PARAM_MOUTH_FORM", 0.4f);
            addDual("ParamAngleZ", "PARAM_ANGLE_Z", -5f);
        }
        // 临时反应属于这个模型实例，结束后自然回到原情绪，不写回业务状态。
        if (elapsed < reactionUntil) {
            float strength = Math.min(1f, (reactionUntil - elapsed) / 0.4f);
            if (reaction.equals("HEAD") || reaction.equals("STROKE")) {
                addDual("ParamMouthForm", "PARAM_MOUTH_FORM", 0.65f * strength);
                addDual("ParamEyeLSmile", "PARAM_EYE_L_SMILE", 0.75f * strength);
                addDual("ParamEyeRSmile", "PARAM_EYE_R_SMILE", 0.75f * strength);
                addDual("ParamAngleZ", "PARAM_ANGLE_Z", (reaction.equals("HEAD") ? 7f : -4f) * strength);
            }
        }
        if (physics != null) physics.evaluate(model, delta);
        if (pose != null) pose.updateParameters(model, delta);
        model.update();
        CubismMatrix44 projection = CubismMatrix44.create();
        float aspect = (float) width / height;
        if (model.getCanvasHeight() / model.getCanvasWidth() < 1f / aspect) {
            modelMatrix.setWidth(2f);
            projection.scale(1f, aspect);
        } else {
            modelMatrix.setHeight(2f);
            projection.scale(1f / aspect, 1f);
        }
        // 桌面窗口使用按模型配置的校准取景，页面维持原比例。
        if (compactFraming) {
            float zoom = compactScale / aspect;
            projection.scaleRelative(zoom, zoom);
        }
        projection.multiplyByMatrix(modelMatrix);
        CubismRendererAndroid renderer = getRenderer();
        renderer.setMvpMatrix(projection);
        renderer.drawModel();
    }

    /** 兼容 Cubism 3/4/5 标准参数名与 Cubism 2 转换模型的大写参数名（如 Wanko）。 */
    private void addDual(String standardName, String legacyName, float value) {
        model.addParameterValue(CubismFramework.getIdManager().getId(standardName), value);
        model.addParameterValue(CubismFramework.getIdManager().getId(legacyName), value);
    }

    private void setDual(String standardName, String legacyName, float value) {
        model.setParameterValue(CubismFramework.getIdManager().getId(standardName), value);
        model.setParameterValue(CubismFramework.getIdManager().getId(legacyName), value);
    }

    void resize(int width, int height) { this.width = width; this.height = height; setRenderTargetSize(width, height); }

    void close() {
        motionManager.stopAllMotions();
        delete();
        for (int texture : textures) GLES20.glDeleteTextures(1, new int[]{texture}, 0);
        textures.clear();
        idle.clear();
        tapMotions.clear();
    }
}
