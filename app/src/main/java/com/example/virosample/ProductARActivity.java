/*
 * Copyright (c) 2017-present, Viro, Inc.
 * All rights reserved.
 *
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
 */
package com.example.virosample;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.virosample.model.Product;
import com.viro.core.ARAnchor;
import com.viro.core.ARHitTestListener;
import com.viro.core.ARHitTestResult;
import com.viro.core.ARNode;
import com.viro.core.ARScene;
import com.viro.core.AmbientLight;
import com.viro.core.AnimationTimingFunction;
import com.viro.core.AnimationTransaction;
import com.viro.core.AsyncObject3DListener;
import com.viro.core.ClickListener;
import com.viro.core.ClickState;
import com.viro.core.DragListener;
import com.viro.core.GestureRotateListener;
import com.viro.core.Material;
import com.viro.core.Node;
import com.viro.core.Object3D;
import com.viro.core.Portal;
import com.viro.core.PortalScene;
import com.viro.core.RendererConfiguration;
import com.viro.core.RotateState;
import com.viro.core.Sound;
import com.viro.core.Spotlight;
import com.viro.core.Surface;
import com.viro.core.Texture;
import com.viro.core.Vector;
import com.viro.core.ViroMediaRecorder;
import com.viro.core.ViroView;
import com.viro.core.ViroViewARCore;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
/**
 *  A ViroCore ProductARActivity that coordinates the placing of a Product last selected in the
 * {@link ProductSelectionActivity} in AR.
 */
public class ProductARActivity extends Activity {

    private static final String TAG = ProductARActivity.class.getSimpleName();
    final public static String INTENT_PRODUCT_KEY = "product_key";

    private ViroView mViroView;
    private View mHudGroupView;
    private TextView mHUDInstructions;
    private ImageView mCameraButton;
  //  private View mIconShakeView;

    /*
     The Tracking status is used to coordinate the displaying of our 3D controls and HUD
     UI as the user looks around the tracked AR Scene.
     */
    private enum TRACK_STATUS{
        FINDING_SURFACE,
        SURFACE_NOT_FOUND,
        SURFACE_FOUND,
        SELECTED_SURFACE;
    }

    private TRACK_STATUS mStatus = TRACK_STATUS.SURFACE_NOT_FOUND;
    private Product mSelectedProduct = null;
    private Node mProductModelGroup = null;
    private Node mCrosshairModel = null;
    private ARScene mScene = null;
    private AmbientLight mMainLight = null;
    private Vector mLastProductRotation = new Vector();
    private Vector mSavedRotateToRotation = new Vector();
    private ARHitTestListenerCrossHair mCrossHairHitTest = null;
    private AssetManager mAssetManager;
    private PortalScene mPortalScene;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.product_activity_main_layout);
        RendererConfiguration config = new RendererConfiguration();
        config.setShadowsEnabled(true);
        config.setBloomEnabled(true);
        config.setHDREnabled(true);
        config.setPBREnabled(true);


        mViroView = new ViroViewARCore(this, new ViroViewARCore.StartupListener() {
            @Override
            public void onSuccess() {
                displayScene();
            }

            @Override
            public void onFailure(ViroViewARCore.StartupError error, String errorMessage) {
                Log.e(TAG, "Failed to load AR Scene [" + errorMessage + "]");
            }
        }, config);
        setContentView(mViroView);


        View.inflate(this, R.layout.ar_hud, ((ViewGroup) mViroView));
        mHudGroupView = (View) findViewById(R.id.main_hud_layout);
        //mHudGroupView.setVisibility(View.GONE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mViroView.onActivityStarted(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mViroView.onActivityResumed(this);
    }

    @Override
    protected void onPause(){
        super.onPause();
        mViroView.onActivityPaused(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mViroView.onActivityStopped(this);
    }

    @Override
    protected void onDestroy(){
        ((ViroViewARCore)mViroView).setCameraARHitTestListener(null);
        mViroView.onActivityDestroyed(this);
        super.onDestroy();
    }

    private void requestPermissions(){
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                0);
    }

    private static boolean hasRecordingStoragePermissions(Context context) {
        boolean hasExternalStoragePerm = ContextCompat.checkSelfPermission(context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        return hasExternalStoragePerm;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        if (!hasRecordingStoragePermissions(ProductARActivity.this)) {
            Toast toast = Toast.makeText(ProductARActivity.this, "User denied permissions", Toast.LENGTH_LONG);
            toast.show();
        }
    }


    private void displayScene() {
        // Create the ARScene within which to load our ProductAR Experience
        mScene = new ARScene();
        mMainLight = new AmbientLight(Color.parseColor("#606060"), 400);
        mMainLight.setInfluenceBitMask(3);
        mScene.getRootNode().addLight(mMainLight);

        // Setup our 3D and HUD controls
        initARCrosshair(mScene);
        init3DModelProduct(mScene);
        initARHud();

        // Start our tracking UI when the scene is ready to be tracked
        mScene.setListener(new ARSceneListener());

        // Finally set the arScene on the renderer
        mViroView.setScene(mScene);
    }



    private void initARHud(){
        // TextView instructions
        //mHUDInstructions = (TextView) mViroView.findViewById(R.id.ar_hud_instructions);
        mViroView.findViewById(R.id.bottom_frame_controls).setVisibility(View.VISIBLE);

        // Bind the back button on the top left of the layout
        ImageView view = (ImageView) findViewById(R.id.ar_back_button);
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //init3DModelProduct(mScene);
                //createPortal(mScene, "jungle");
                //update3DModelProduct();
            }
        });

        // Bind the detail buttons on the top right of the layout.
        ImageView productDetails = (ImageView) findViewById(R.id.ar_details_page);
        productDetails.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                createObject(mCrosshairModel.getPositionRealtime());


            }
        });

        // Bind the camera button on the bottom, for taking images.
        mCameraButton  = (ImageView) mViroView.findViewById(R.id.ar_photo_button);
        //final File photoFile = new File(getFilesDir(), "screenShot");
        mCameraButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

            }

        });
        mCameraButton.setVisibility(View.VISIBLE);

        //mIconShakeView = mViroView.findViewById(R.id.icon_shake_phone);
    }

    private void initARCrosshair(ARScene scene){
        if (mCrosshairModel != null){
            return;
        }

        final Object3D crosshairModel = new Object3D();
        scene.getRootNode().addChildNode(crosshairModel);
        crosshairModel.loadModel(Uri.parse("file:///android_asset/tracking_1.vrx"), Object3D.Type.FBX, new AsyncObject3DListener() {
            @Override
            public void onObject3DLoaded(Object3D object3D, Object3D.Type type) {

                mCrosshairModel = object3D;
                mCrosshairModel.setOpacity(0);
                mCrosshairModel.setScale(new Vector(0.175,0.175,0.175));
                mCrosshairModel.setClickListener(new ClickListener() {
                    @Override
                    public void onClick(int i, Node node, Vector vector) {
                        setTrackingStatus(TRACK_STATUS.SELECTED_SURFACE);
                        update3DModelProduct();
                    }

                    @Override
                    public void onClickState(int i, Node node, ClickState clickState, Vector vector) {
                        // No-op
                    }
                });
            }

            @Override
            public void onObject3DFailed(String error) {
                Log.e("Viro"," Model load failed : " + error);
            }
        });
    }

    private void init3DModelProduct(ARScene scene){
        // Create our group node containing the light, shadow plane, and 3D models
        mProductModelGroup = new Node();

        // Create a light to be shined on the model.
        Spotlight spotLight = new Spotlight();
        spotLight.setInfluenceBitMask(1);
        spotLight.setPosition(new Vector(0,5,0));
        spotLight.setCastsShadow(true);
        spotLight.setAttenuationEndDistance(7);
        spotLight.setAttenuationStartDistance(4);
        spotLight.setDirection(new Vector(0,-1,0));
        spotLight.setIntensity(6000);
        spotLight.setShadowOpacity(0.35f);
        mProductModelGroup.addLight(spotLight);

        // Create a mock shadow plane in AR
        Node shadowNode = new Node();
        Surface shadowSurface = new Surface(20,20);
        Material material = new Material();
        material.setShadowMode(Material.ShadowMode.TRANSPARENT);
        material.setLightingModel(Material.LightingModel.LAMBERT);
        shadowSurface.setMaterials(Arrays.asList(material));
        shadowNode.setGeometry(shadowSurface);
        shadowNode.setLightReceivingBitMask(1);
        shadowNode.setPosition(new Vector(0,-0.01,0));
        shadowNode.setRotation(new Vector(-1.5708,0,0));
        mProductModelGroup.addChildNode(shadowNode);

        // Load the model from the given mSelected Product
        final Object3D portalModel = new Object3D();
        portalModel.loadModel(Uri.parse("file:///android_asset/portal_ship.vrx"), Object3D.Type.FBX, new AsyncObject3DListener() {
            @Override
            public void onObject3DLoaded(Object3D object3D, Object3D.Type type) {
                object3D.setLightReceivingBitMask(1);
                mProductModelGroup.setOpacity(0);
                mProductModelGroup.setScale(new Vector(0.9, 0.9, 0.9));
                mLastProductRotation = object3D.getRotationEulerRealtime();
            }

            @Override
            public void onObject3DFailed(String error) {
                Log.e("Viro"," Model load failed : " + error);
            }
        });

        Portal portal = new Portal();
        portal.addChildNode(portalModel);
        portal.setScale(new Vector(0.5, 0.5, 0.5));

        // Create a PortalScene that uses the Portal as an entrance.
        PortalScene portalScene = new PortalScene();
        portalScene.setPosition(new Vector(0, 0, -2));
        portalScene.setPassable(true);
        portalScene.setPortalEntrance(portal);
        portalScene.setEntryListener(new PortalScene.EntryListener() {
            Sound bird = new Sound(mViroView.getViroContext(), Uri.parse("file///android_asset/jungle.mp3"), null);

            @Override
            public void onPortalEnter(PortalScene portalScene) {
                bird.setLoop(false);
                bird.play();

            }

            @Override
            public void onPortalExit(PortalScene portalScene) {
                bird.pause();

            }
        });


        // Add a 'jungle' background for the Portal scene
        final Bitmap background = bitmapFromAsset("jungle.jpg");
        final Texture beachTexture = new Texture(background, Texture.Format.RGBA8, true, false);
        portalScene.setBackgroundTexture(beachTexture);

        mProductModelGroup.setOpacity(0);
        mProductModelGroup.addChildNode(portalScene);
        scene.getRootNode().addChildNode(mProductModelGroup);
    }
    private void createObject(Vector position) {
        Node animalObject = new Node();
        // Create a light to be shined on the model.
        Spotlight spotLight = new Spotlight();
        spotLight.setInfluenceBitMask(1);
        spotLight.setPosition(new Vector(0,5,0));
        spotLight.setCastsShadow(true);
        spotLight.setAttenuationEndDistance(7);
        spotLight.setAttenuationStartDistance(4);
        spotLight.setDirection(new Vector(0,-1,0));
        spotLight.setIntensity(6000);
        spotLight.setShadowOpacity(0.35f);
        animalObject.addLight(spotLight);

        // Create a mock shadow plane in AR
        Node shadowNode = new Node();
        Surface shadowSurface = new Surface(10,10);
        Material material = new Material();
        material.setShadowMode(Material.ShadowMode.TRANSPARENT);
        material.setLightingModel(Material.LightingModel.LAMBERT);
        shadowSurface.setMaterials(Arrays.asList(material));
        shadowNode.setGeometry(shadowSurface);
        shadowNode.setLightReceivingBitMask(1);
        shadowNode.setPosition(new Vector(0,-0.01,0));
        shadowNode.setRotation(new Vector(-1.5708,0,0));
        animalObject.addChildNode(shadowNode);

        final Object3D object = new Object3D();
        object.setPosition(position);
        mScene.getRootNode().addChildNode(object);
        final Bitmap texture = bitmapFromAsset("Diffuse.png");
        object.loadModel(Uri.parse("file:///android_asset/dog.obj"), Object3D.Type.OBJ, new AsyncObject3DListener() {
            @Override
            public void onObject3DLoaded(Object3D object3D, Object3D.Type type) {
                Texture objectTexture = new Texture(texture, Texture.Format.RGBA8, false, false);
                Material material = new Material();
                material.setDiffuseTexture(objectTexture);
                material.setLightingModel(Material.LightingModel.PHYSICALLY_BASED);

                object.getGeometry().setMaterials(Arrays.asList(material));

            }

            @Override
            public void onObject3DFailed(String s) {

            }
        });
        object.setScale(new Vector(0.75,0.75,0.75));
        animalObject.addChildNode(object);
        mScene.getRootNode().addChildNode(animalObject);
    }

    private Bitmap bitmapFromAsset(String assetName) {
        if (mAssetManager == null) {
            mAssetManager = getResources().getAssets();
        }

        InputStream imageStream;
        try {
            imageStream = mAssetManager.open(assetName);
        } catch (IOException exception) {
            Log.w("Viro", "Unable to find image [" + assetName + "] in assets! Error: "
                    + exception.getMessage());
            return null;
        }
        return BitmapFactory.decodeStream(imageStream);
    }

    private void setTrackingStatus(TRACK_STATUS status) {
        if (mStatus == TRACK_STATUS.SELECTED_SURFACE || mStatus == status){
            return;
        }

        // If the surface has been selected, we no longer need our cross hair listener.
        if (status == TRACK_STATUS.SELECTED_SURFACE){
            ((ViroViewARCore)mViroView).setCameraARHitTestListener(null);
        }

        mStatus = status;
        update3DARCrosshair();
        //update3DModelProduct();
    }

    private void update3DARCrosshair(){
        switch(mStatus){
            case FINDING_SURFACE:
            case SURFACE_NOT_FOUND:
            case SELECTED_SURFACE:
                mCrosshairModel.setOpacity(0);
                break;
            case SURFACE_FOUND:
                mCrosshairModel.setOpacity(1);
                break;
        }

        if (mStatus == TRACK_STATUS.SELECTED_SURFACE && mCrossHairHitTest != null){
            mCrossHairHitTest = null;
            ((ViroViewARCore)mViroView).setCameraARHitTestListener(null);
        } else if (mCrossHairHitTest == null) {
            mCrossHairHitTest = new ARHitTestListenerCrossHair();
            ((ViroViewARCore)mViroView).setCameraARHitTestListener(mCrossHairHitTest);
        }
    }

    private void update3DModelProduct(){
        // Hide the product if the user has not placed it yet.
//        if (mStatus != TRACK_STATUS.SELECTED_SURFACE){
//            mProductModelGroup.setOpacity(0);
//            return;
//        }

        Vector position = mCrosshairModel.getPositionRealtime();
        Vector rotation = mCrosshairModel.getRotationEulerRealtime();

        mProductModelGroup.setOpacity(1);
        mProductModelGroup.setPosition(position);
        mProductModelGroup.setRotation(rotation);
        Sound jumanji = new Sound(mViroView.getViroContext(), Uri.parse("file:///android_asset/jumanji.mp3"), null);
        jumanji.setLoop(false);
        jumanji.play();
    }

    private void onModelClick(ClickState state){
        if (state == ClickState.CLICK_DOWN){
            mLastProductRotation = mProductModelGroup.getRotationEulerRealtime();
        } else if (state == ClickState.CLICK_UP) {
            mLastProductRotation = mSavedRotateToRotation;
        }
    }

    private class ARHitTestListenerCrossHair implements ARHitTestListener {
        @Override
        public void onHitTestFinished(ARHitTestResult[] arHitTestResults) {
            if( arHitTestResults == null || arHitTestResults.length <=0) {
                return;
            }

            // If we have found intersected AR Hit points, update views as needed, reset miss count.
            ViroViewARCore viewARView = (ViroViewARCore)mViroView;
            final Vector cameraPos  = viewARView.getLastCameraPositionRealtime();

            // Grab the closest ar hit target
            float closestDistance = Float.MAX_VALUE;
            ARHitTestResult result = null;
            for (int i = 0; i < arHitTestResults.length; i++) {
                ARHitTestResult currentResult = arHitTestResults[i];

                float distance = currentResult.getPosition().distance(cameraPos);
                if (distance < closestDistance && distance > .3 && distance < 5){
                    result = currentResult;
                    closestDistance = distance;
                }
            }

            // Update the cross hair target location with the closest target.
            animateCrossHairToPosition(result);

            // Update State based on hit target
            if (result != null){
                setTrackingStatus(TRACK_STATUS.SURFACE_FOUND);
            } else {
                setTrackingStatus(TRACK_STATUS.FINDING_SURFACE);
            }
        }

        private void animateCrossHairToPosition(ARHitTestResult result){
            if (result == null) {
                return;
            }

            AnimationTransaction.begin();
            AnimationTransaction.setAnimationDuration(70);
            AnimationTransaction.setTimingFunction(AnimationTimingFunction.EaseOut);
            mCrosshairModel.setPosition(result.getPosition());
            mCrosshairModel.setRotation(result.getRotation());
            AnimationTransaction.commit();
        }
    }

    protected class ARSceneListener implements ARScene.Listener {
        @Override
        public void onTrackingInitialized() {
            // The Renderer is ready - turn everything visible.
            //mHudGroupView.setVisibility(View.VISIBLE);

            // Update our UI views to the finding surface state.
            setTrackingStatus(TRACK_STATUS.FINDING_SURFACE);
        }

        @Override
        public void onAmbientLightUpdate(float lightIntensity, float colorTemperature) {
            // no-op
        }

        @Override
        public void onAnchorFound(ARAnchor anchor, ARNode arNode) {
            // no-op
        }

        @Override
        public void onAnchorUpdated(ARAnchor anchor, ARNode arNode) {
            // no-op
        }

        @Override
        public void onAnchorRemoved(ARAnchor anchor, ARNode arNode) {
            // no-op
        }
    }

}
