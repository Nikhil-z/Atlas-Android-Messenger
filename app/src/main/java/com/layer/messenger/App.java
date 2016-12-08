package com.layer.messenger;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.AsyncTask;
import android.os.StrictMode;

import com.layer.atlas.messagetypes.text.TextCellFactory;
import com.layer.atlas.messagetypes.threepartimage.ThreePartImageUtils;
import com.layer.atlas.util.Util;
import com.layer.atlas.util.picasso.requesthandlers.MessagePartRequestHandler;
import com.layer.messenger.util.AuthenticationProvider;
import com.layer.messenger.util.Log;
import com.layer.sdk.LayerClient;
import com.squareup.picasso.Picasso;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

/**
 * App provides static access to a LayerClient and other Atlas and Messenger context, including
 * AuthenticationProvider, ParticipantProvider, Participant, and Picasso.
 *
 * App.Flavor allows build variants to target different environments, such as the Atlas Demo and the
 * open source Rails Identity Provider.  Switch flavors with the Android Studio `Build Variant` tab.
 * When using a flavor besides the Atlas Demo you must manually set your Layer App ID and GCM Sender
 * ID in that flavor's Flavor.java.
 *
 * @see com.layer.messenger.App.Flavor
 * @see com.layer.messenger.flavor.Flavor
 * @see LayerClient
 * @see Picasso
 * @see AuthenticationProvider
 */
public class App extends Application {

    private static Application sInstance;
    private static Flavor sFlavor = new com.layer.messenger.flavor.Flavor();

    private static LayerClient sLayerClient;
    private static AuthenticationProvider sAuthProvider;
    private static Picasso sPicasso;
    private static CountDownLatch sLayerClientCreationLatch;


    //==============================================================================================
    // Application Overrides
    //==============================================================================================

    @Override
    public void onCreate() {
        super.onCreate();

        // Enable verbose logging in debug builds
        if (BuildConfig.DEBUG) {
            com.layer.atlas.util.Log.setLoggingEnabled(true);
            com.layer.messenger.util.Log.setAlwaysLoggable(true);
            LayerClient.setLoggingEnabled(this, true);

            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build());
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build());
        }

        if (Log.isPerfLoggable()) {
            Log.perf("Application onCreate()");
        }

        // Allow the LayerClient to track app state
        LayerClient.applicationCreated(this);

        sInstance = this;

        // Create a LayerClient instance off of the main thread
        createLayerClientAsynchronously();
    }

    public static Application getInstance() {
        return sInstance;
    }


    //==============================================================================================
    // Identity Provider Methods
    //==============================================================================================

    /**
     * Routes the user to the proper Activity depending on their authenticated state.  Returns
     * `true` if the user has been routed to another Activity, or `false` otherwise.
     *
     * @param from Activity to route from.
     * @return `true` if the user has been routed to another Activity, or `false` otherwise.
     */
    public static boolean routeLogin(Activity from) {
        LayerClient layerClient = getLayerClient();
        return getAuthenticationProvider().routeLogin(layerClient, getLayerAppId(), from);
    }

    /**
     * Authenticates with the AuthenticationProvider and Layer, returning asynchronous results to
     * the provided callback.
     *
     * @param credentials Credentials associated with the current AuthenticationProvider.
     * @param callback    Callback to receive authentication results.
     */
    @SuppressWarnings("unchecked")
    public static void authenticate(Object credentials, AuthenticationProvider.Callback callback) {
        LayerClient client = getLayerClient();
        if (client == null) return;
        String layerAppId = getLayerAppId();
        if (layerAppId == null) return;
        getAuthenticationProvider()
                .setCredentials(credentials)
                .setCallback(callback);
        client.authenticate();
    }

    /**
     * Deauthenticates with Layer and clears cached AuthenticationProvider credentials.
     *
     * @param callback Callback to receive deauthentication success and failure.
     */
    public static void deauthenticate(final Util.DeauthenticationCallback callback) {
        Util.deauthenticate(getLayerClient(), new Util.DeauthenticationCallback() {
            @Override
            @SuppressWarnings("unchecked")
            public void onDeauthenticationSuccess(LayerClient client) {
                getAuthenticationProvider().setCredentials(null);
                callback.onDeauthenticationSuccess(client);
            }

            @Override
            public void onDeauthenticationFailed(LayerClient client, String reason) {
                callback.onDeauthenticationFailed(client, reason);
            }
        });
    }


    //==============================================================================================
    // Getters / Setters
    //==============================================================================================

    /**
     * Gets a LayerClient. Returns `null` if the LayerClient hasn't been created yet, or if the
     * the flavor was unable to create a LayerClient (due to no App ID, etc.).
     *
     * @return New or existing LayerClient, or `null` if a LayerClient could not be constructed.
     * @see App#createLayerClientAsynchronously()
     * @see Flavor#generateLayerClient(Context, LayerClient.Options)
     */
    public static LayerClient getLayerClient() {
        awaitLayerClientCreation();
        return sLayerClient;
    }

    /**
     * If a LayerClient creation is in progress, make the current thread wait until the creation
     * is completed.
     */
    private static void awaitLayerClientCreation() {
        if (sLayerClientCreationLatch != null) {
            try {
                sLayerClientCreationLatch.await();
            } catch (InterruptedException e) {
                Log.e("Thread interrupted while waiting for LayerClient initialization");
            }
        }
    }

    /**
     * Creates a LayerClient, using a default set of LayerClient.Options and flavor-specific
     * App ID and Options from the `generateLayerClient` method. This performs the creation in
     * an AsyncTask.
     *
     * @see Flavor#generateLayerClient(Context, LayerClient.Options)
     */
    public static void createLayerClientAsynchronously() {
        // You may only want to do this when the LayerClient is null to avoid multiple calls
        // to `LayerClient.newInstance` when an instance already exists. This is currently allowed
        // to provide support for switching environments via a `CustomEndpoint`.

        sLayerClientCreationLatch = new CountDownLatch(1);
        new AsyncTask<Void, Void, Void>() {

            @Override
            protected Void doInBackground(Void... params) {
                try {
                    // Custom options for constructing a LayerClient
                    LayerClient.Options options = new LayerClient.Options()

                    /* Fetch the minimum amount per conversation when first authenticated */
                            .historicSyncPolicy(LayerClient.Options.HistoricSyncPolicy.FROM_LAST_MESSAGE)

                    /* Automatically download text and ThreePartImage info/preview */
                            .autoDownloadMimeTypes(Arrays.asList(
                                    TextCellFactory.MIME_TYPE,
                                    ThreePartImageUtils.MIME_TYPE_INFO,
                                    ThreePartImageUtils.MIME_TYPE_PREVIEW));

                    // Allow flavor to specify Layer App ID and customize Options.
                    sLayerClient = sFlavor.generateLayerClient(sInstance, options);

                    // Flavor was unable to generate Layer Client (no App ID, etc.)
                    if (sLayerClient == null) {
                        return null;
                    }

                    /* Register AuthenticationProvider for handling authentication challenges */
                    sLayerClient.registerAuthenticationListener(getAuthenticationProvider());
                    return null;
                } finally {
                    sLayerClientCreationLatch.countDown();
                }
            }
        }.execute();
    }

    public static String getLayerAppId() {
        return sFlavor.getLayerAppId();
    }

    public static AuthenticationProvider getAuthenticationProvider() {
        if (sAuthProvider == null) {
            sAuthProvider = sFlavor.generateAuthenticationProvider(sInstance);

            // If we have cached credentials, try authenticating with Layer
            if (sLayerClient != null && sAuthProvider.hasCredentials()) sLayerClient.authenticate();
        }
        return sAuthProvider;
    }

    public static Picasso getPicasso() {
        if (sPicasso == null) {
            // Picasso with custom RequestHandler for loading from Layer MessageParts.
            sPicasso = new Picasso.Builder(sInstance)
                    .addRequestHandler(new MessagePartRequestHandler(getLayerClient()))
                    .build();
        }
        return sPicasso;
    }

    /**
     * Flavor is used by Atlas Messenger to switch environments.
     *
     * @see com.layer.messenger.flavor.Flavor
     */
    public interface Flavor {
        String getLayerAppId();

        LayerClient generateLayerClient(Context context, LayerClient.Options options);

        AuthenticationProvider generateAuthenticationProvider(Context context);
    }
}
