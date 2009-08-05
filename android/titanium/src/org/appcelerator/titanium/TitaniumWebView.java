package org.appcelerator.titanium;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import org.appcelerator.titanium.api.ITitaniumApp;
import org.appcelerator.titanium.api.ITitaniumLifecycle;
import org.appcelerator.titanium.api.ITitaniumNativeControl;
import org.appcelerator.titanium.api.ITitaniumNetwork;
import org.appcelerator.titanium.api.ITitaniumPlatform;
import org.appcelerator.titanium.api.ITitaniumView;
import org.appcelerator.titanium.config.TitaniumAppInfo;
import org.appcelerator.titanium.config.TitaniumConfig;
import org.appcelerator.titanium.config.TitaniumWindowInfo;
import org.appcelerator.titanium.module.TitaniumAPI;
import org.appcelerator.titanium.module.TitaniumAccelerometer;
import org.appcelerator.titanium.module.TitaniumAnalytics;
import org.appcelerator.titanium.module.TitaniumApp;
import org.appcelerator.titanium.module.TitaniumDatabase;
import org.appcelerator.titanium.module.TitaniumFilesystem;
import org.appcelerator.titanium.module.TitaniumGeolocation;
import org.appcelerator.titanium.module.TitaniumGesture;
import org.appcelerator.titanium.module.TitaniumMedia;
import org.appcelerator.titanium.module.TitaniumNetwork;
import org.appcelerator.titanium.module.TitaniumPlatform;
import org.appcelerator.titanium.module.TitaniumUI;
import org.appcelerator.titanium.module.analytics.TitaniumAnalyticsEventFactory;
import org.appcelerator.titanium.module.ui.TitaniumMenuItem;
import org.appcelerator.titanium.util.Log;
import org.appcelerator.titanium.util.TitaniumFileHelper;
import org.appcelerator.titanium.util.TitaniumUrlHelper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.AbsoluteLayout;

@SuppressWarnings("deprecation")
public class TitaniumWebView extends WebView
	implements Handler.Callback, ITitaniumView, ITitaniumLifecycle
{
	private static final String LCAT = "TiWebView";
	private static final boolean DBG = TitaniumConfig.LOGD;

	private static final String JAVASCRIPT = "javascript:";
	private static final String TITANIUM_CALLBACK = "Titanium.callbacks"; //Sent from ti.js

	public static final int MSG_RUN_JAVASCRIPT = 300;
	public static final int MSG_LOAD_FROM_SOURCE = 301;
	public static final int MSG_ADD_CONTROL = 302;

	protected static final String MSG_EXTRA_URL = "url";
	protected static final String MSG_EXTRA_SOURCE = "source";

	private Handler handler;
	private Handler evalHandler;

	private TitaniumModuleManager tmm;
	private TitaniumUI tiUI;

	private MimeTypeMap mtm;

	private HashMap<String, ITitaniumNativeControl> nativeControls;
	private AbsoluteLayout.LayoutParams offScreen;

	private HashMap<String, Semaphore> locks;
	private AtomicInteger uniqueLockId;

	private String url;
	private String source;
	private Semaphore sourceReady;
	private boolean useAsView;

	private HashSet<OnConfigChange> configurationChangeListeners;

	public interface OnConfigChange {
		public void configurationChanged(Configuration config);
	}

	private HashMap<Integer, String> optionMenuCallbacks;

	public TitaniumWebView(TitaniumActivity activity, String url) {
		this(activity, url, false);
	}

	public TitaniumWebView(TitaniumActivity activity, String url, boolean useAsView)
	{
		super(activity);

		this.useAsView = useAsView;

		this.handler = new Handler(this);
		this.mtm = MimeTypeMap.getSingleton();
		this.locks = new HashMap<String,Semaphore>();
		this.uniqueLockId = new AtomicInteger();
        this.tmm = new TitaniumModuleManager(activity, this);
        this.url = url;
		this.configurationChangeListeners = new HashSet<OnConfigChange>();


        setWebViewClient(new TiWebViewClient(activity));
        setWebChromeClient(new TiWebChromeClient(activity, useAsView));

		WebSettings settings = getSettings();

		setVerticalScrollbarOverlay(true);

        settings.setJavaScriptEnabled(true);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportZoom(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setLightTouchEnabled(true);

        offScreen = new AbsoluteLayout.LayoutParams(1, 1, -100, -100);
        final TitaniumWebView me = this;

        HandlerThread ht = new HandlerThread("TiJSEvalThread"){

			@Override
			protected void onLooperPrepared() {
				super.onLooperPrepared();
				evalHandler = new Handler(Looper.myLooper(), new Handler.Callback(){

					public boolean handleMessage(Message msg) {
						if(msg.what == MSG_RUN_JAVASCRIPT) {
							if (DBG) {
								Log.d(LCAT, "Invoking: " + msg.obj);
							}
							String id = msg.getData().getString("syncId");
							loadUrl((String) msg.obj);
							syncOn(id);
							if (DBG) {
								Log.w(LCAT, "AFTER: " + msg.obj);
							}

							return true;
						}
						return false;
					}});
				synchronized(me) {
					me.notify();
				}
			}

        };
        ht.start();
        // wait for eval hander to initialize
        synchronized(me) {
        	try {
        		me.wait();
        	} catch (InterruptedException e) {

        	}
        }

        sourceReady = new Semaphore(0);
        final String furl = url;
		Thread sourceLoadThread = new Thread(new Runnable(){

			public void run() {
				try {
					TitaniumApplication app = tmm.getApplication();
					source = TitaniumUrlHelper.getSource(app, app.getApplicationContext(), furl, null);
					Log.i(LCAT, "Source loaded for " + furl);
				} catch (IOException e) {
					Log.e(LCAT, "Unable to load source for " + furl);
				} finally {
					sourceReady.release();
				}
			}});
        sourceLoadThread.start();


        initializeModules();
		buildWebView();
	}

    protected void initializeModules() {
        // Add Modules
        this.tiUI = new TitaniumUI(tmm, "TitaniumUI");
        TitaniumAppInfo appInfo = tmm.getActivity().getAppInfo();

        new TitaniumMedia(tmm, "TitaniumMedia");
        String userAgent = appInfo.getSystemProperties().getString(TitaniumAppInfo.PROP_NETWORK_USER_AGENT, null); //if we get null, we have a startup error.
        ITitaniumNetwork tiNetwork = new TitaniumNetwork(tmm, "TitaniumNetwork", userAgent);
        ITitaniumPlatform tiPlatform = new TitaniumPlatform(tmm, "TitaniumPlatform");

		ITitaniumApp tiApp = new TitaniumApp(tmm, "TitaniumApp",appInfo);
 		new TitaniumAnalytics(tmm, "TitaniumAnalytics");
		new TitaniumAPI(tmm, "TitaniumAPI");
		new TitaniumFilesystem(tmm, "TitaniumFilesystem");
		new TitaniumDatabase(tmm, "TitaniumDatabase");
		new TitaniumAccelerometer(tmm, "TitaniumAccelerometer");
		new TitaniumGesture(tmm, "TitaniumGesture");
		new TitaniumGeolocation(tmm, "TitaniumGeolocation");

		// Add Modules from Applications
		TitaniumApplication app = tmm.getApplication();
		app.addModule(tmm);

		tmm.registerModules();

		if (!useAsView) {
			if (app.needsEnrollEvent()) {
				app.postAnalyticsEvent(TitaniumAnalyticsEventFactory.createAppEnrollEvent(tiPlatform, tiApp));
			}

			if (app.needsStartEvent()) {
				String deployType = appInfo.getSystemProperties().getString("ti.deploytype", "unknown");

				app.postAnalyticsEvent(TitaniumAnalyticsEventFactory.createAppStartEvent(tiNetwork, tiPlatform, tiApp, deployType));
			}
		}
    }

    protected void buildWebView()
    {
    	if (DBG) {
    		Log.d(LCAT, "buildWebView");
    	}
    	if (!useAsView) {
	    	TitaniumWindowInfo windowInfo = tmm.getActivity().getWindowInfo();

			if (windowInfo != null && windowInfo.hasBackgroundColor()) {
				setBackgroundColor(windowInfo.getBackgroundColor());
			}
    	}
        if (url != null)
		{
        	try {
        		Log.i(LCAT, "Waiting for source " + url);
      			sourceReady.acquire();
          		Log.i(LCAT, "Loading source");
          		loadFromSource(url, source);
        	} catch (InterruptedException e) {
        		Log.w(LCAT, "Interrupted: " + e.getMessage());
        	}
	    }
		else
		{
			if (DBG) {
				Log.d(LCAT, "url was empty");
			}
		}
    }

	public String registerLock() {
		String syncId = "S:" + uniqueLockId.incrementAndGet();
		synchronized(locks) {
			Semaphore l = locks.get(syncId);
			if (l != null) {
				throw new IllegalStateException("Attempt to register duplicate lock id: " + syncId);
			}
			l = new Semaphore(0);
			locks.put(syncId, l);
			return syncId;
		}
	}

	public Semaphore getLockFor(String syncId) {
		synchronized(locks) {
			return locks.get(syncId);
		}
	}

	public void unregisterLock(String syncId) {
		synchronized(locks) {
			if (locks.containsKey(syncId)) {
				locks.remove(syncId);
			}
		}
	}

	public void signal(String syncId) {
		if (DBG) {
			Log.d(LCAT, "Signaling " + syncId);
		}
		Semaphore l = null;
		synchronized(locks) {
			l = locks.get(syncId);
		}
			l.release();
	}

	public void evalJS(final String method) {
		evalJS(method, (String) null, (String) null);
	}

	public void evalJS(final String method, final String data) {
		evalJS(method, data, (String) null);
	}

	public void evalJS(final String method, final JSONObject data)
	{
		String dataValue = null;

		if (data != null) {
			dataValue = data.toString();
		}

		evalJS(method, dataValue, (String) null);
	}

	private void syncOn(String syncId) {
		if (syncId != null) {
			Semaphore l = null;
			synchronized(locks) {
				l = locks.get(syncId);
			}
			try {
				l.acquire();
			} catch (InterruptedException e) {

			}
		}
	}

	public void evalJS(final String method, final String data, final String syncId)
	{
		String expr = method;
		if (expr != null && expr.startsWith(TITANIUM_CALLBACK)) {
			if (data != null) {
				if (syncId == null) {
					expr += ".invoke(" + data + ")";
				}  else {
					expr += ".invoke(" + data + ",'" + syncId + "')";
				}
			} else {
				if (syncId == null) {
					expr += ".invoke()";
				} else {
					expr += ".invoke(null,'" + syncId + "')";
				}
			}
			if (DBG) {
				Log.d(LCAT, expr);
			}
		}

		if (handler != null) {
			if (!expr.startsWith(JAVASCRIPT)) {
				expr = JAVASCRIPT + expr;
			}

			if (DBG) {
				Log.w(LCAT, " BEFORE: " + expr);
			}
			final String f = expr;
			// If someone tries to invoke from WebViewCoreThread, use our eval thread
			if ("WebViewCoreThread".equals(Thread.currentThread().getName())) {
				Message m = evalHandler.obtainMessage(MSG_RUN_JAVASCRIPT, expr);
				m.getData().putString("syncId", syncId);
				m.sendToTarget();
			} else {
				loadUrl(f);
				syncOn(syncId);
				if (DBG) {
					Log.w(LCAT, "AFTER: " + f);
				}
			}
		} else {
			Log.w(LCAT, "Handler not available for dispatching event");
		}

	}

	public boolean handleMessage(Message msg)
	{
		boolean handled = false;
		Bundle b = msg.getData();

		switch (msg.what) {
		case MSG_LOAD_FROM_SOURCE:
      		String url = b.getString(MSG_EXTRA_URL);
      		String source = b.getString(MSG_EXTRA_SOURCE);

      		Log.w(LCAT, "Handling load source message: " + url);

			String extension = MimeTypeMap.getFileExtensionFromUrl(url);
			String mimetype = "application/octet-stream";
			if (extension != null) {
				String type = mtm.getMimeTypeFromExtension(extension);
				if (type != null) {
					mimetype = type;
				} else {
					mimetype = "text/html";
				}

				if("text/html".equals(mimetype)) {

					if (source != null) {
							loadDataWithBaseURL(url, source, mimetype, "utf-8", "about:blank");
							return true;
					} else {
						loadUrl(url); // For testing, doesn't normally run.
					}
				}
			}
			handled = true;
			break;
		case MSG_ADD_CONTROL :
			if (isFocusable()) {
				setFocusable(false);
			}
			View v = (View) msg.obj;
			addView(v, offScreen);
			handled = true;
			break;
		}

		return handled;
	}

	public void loadFromSource(String url, String source)
	{
		Message m = handler.obtainMessage(MSG_LOAD_FROM_SOURCE);
		Bundle b = m.getData();
		b.putString(MSG_EXTRA_URL, url);
		b.putString(MSG_EXTRA_SOURCE, source);
		m.sendToTarget();
	}

	public synchronized void addListener(ITitaniumNativeControl control) {
		String id = control.getHtmlId();

		if (id == null) {
			throw new IllegalArgumentException("Control must have a non-null id");
		}
		if (nativeControls == null) {
			nativeControls = new HashMap<String, ITitaniumNativeControl>();
		} else if(nativeControls.containsKey(id)) {
			throw new IllegalArgumentException("Control has already been registered id=" + id);
		}

		nativeControls.put(id, control);
		//requestNativeLayout(id);

		if (DBG) {
			Log.d(LCAT, "Native control linked to html id " + id);
		}
	}

	public synchronized void removeListener(ITitaniumNativeControl control) {
		if (nativeControls != null) {
			String id = control.getHtmlId();
			if (nativeControls.containsKey(id)) {
				nativeControls.remove(id);
				if (DBG) {
					Log.d(LCAT, "Native control unlinked from html id " + id);
				}
			} else {
				Log.w(LCAT, "Attempt to unlink a non registered control. html id " + id);
			}
		}
	}

	public synchronized void requestNativeLayout() {
		if (nativeControls != null && nativeControls.size() > 0) {
			JSONArray a = new JSONArray();
			for (String id : nativeControls.keySet()) {
				a.put(id);
			}
			requestNativeLayout(a);
		} else {
			if (DBG) {
				Log.d(LCAT, "No native controls, layout request ignored");
			}
		}
	}

	public synchronized void requestNativeLayout(String id)
	{
		JSONArray a = new JSONArray();
		a.put(id);
		requestNativeLayout(a);
	}

	protected void requestNativeLayout(JSONArray a)
	{
		StringBuilder sb = new StringBuilder(256);
		sb.append("Titanium.sendLayoutToNative(")
			.append(a.toString())
			.append(")");

		evalJS(sb.toString());
		sb.setLength(0);
	}

	public void updateNativeControls(String json) {
		try {
			JSONObject o = new JSONObject(json);
			for (String id : nativeControls.keySet()) {
				if (o.has(id)) {
					JSONObject pos = o.getJSONObject(id);
					Bundle b = new Bundle(4);
					b.putInt("top", pos.getInt("top"));
					b.putInt("left", pos.getInt("left"));
					b.putInt("width", pos.getInt("width"));
					b.putInt("height", pos.getInt("height"));

					nativeControls.get(id).handleLayoutRequest(b);
				} else {
					Log.w(LCAT, "Position data not found for id " + id);
				}
			}
		} catch (JSONException e) {
			Log.e(LCAT, "Malformed location object from Titanium.API: " + json);
		}
	}


	public void addControl(View control) {
		handler.obtainMessage(MSG_ADD_CONTROL, control).sendToTarget();
	}

	@Override
	protected void onSizeChanged(int w, int h, int ow, int oh) {
		// TODO Auto-generated method stub
		super.onSizeChanged(w, h, ow, oh);
		handler.post(
				new Runnable() {
					public void run() {
						requestNativeLayout();
					}
		        });
	}

	public void addConfigChangeListener(OnConfigChange listener) {
		synchronized(configurationChangeListeners) {
			configurationChangeListeners.add(listener);
		}
	}

	public void removeConfigChangeListener(OnConfigChange listener) {
		synchronized(configurationChangeListeners) {
			configurationChangeListeners.remove(listener);
		}
	}

	// View methods

	public boolean isPrimary() {
		return false;
	}

	public void dispatchWindowFocusChanged(boolean hasFocus) {
		tiUI.onWindowFocusChanged(hasFocus);
	}

	public void dispatchConfigurationChange(Configuration newConfig) {
		synchronized(configurationChangeListeners) {
			for(OnConfigChange listener : configurationChangeListeners) {
				try {
					listener.configurationChanged(newConfig);
				} catch (Throwable t) {
					Log.e(LCAT, "Error invoking configuration changed on a listener");
				}
			}
		}
	}

	public boolean dispatchPrepareOptionsMenu(Menu menu)
	{
		TitaniumMenuItem md = tiUI.getInternalMenu();
		if (md != null) {
			if (!md.isRoot()) {
				throw new IllegalStateException("Expected root menuitem");
			}

			if (optionMenuCallbacks != null) {
				optionMenuCallbacks.clear();
			}

			optionMenuCallbacks = new HashMap<Integer, String>();
			menu.clear(); // Inefficient, but safest at the moment
			buildMenuTree(menu, md, optionMenuCallbacks);

		} else {
			if (DBG) {
				Log.d(LCAT, "No option menu set.");
			}
			return false;
		}
		return true;
	}

    protected void buildMenuTree(Menu menu, TitaniumMenuItem md, HashMap<Integer, String> map)
    {
    	if (md.isRoot()) {
    		for(TitaniumMenuItem mi : md.getMenuItems()) {
    			buildMenuTree(menu, mi, map);
    		}
    	} else if (md.isSubMenu()) {
    		SubMenu sm = menu.addSubMenu(0, md.getItemId(), 0, md.getLabel());
    		for(TitaniumMenuItem mi : md.getMenuItems()) {
    			buildMenuTree(sm, mi, map);
    		}
    	} else if (md.isSeparator()) {
    		// Skip, no equivalent in Android
    	} else if (md.isItem()) {
    		MenuItem mi = menu.add(0, md.getItemId(), 0, md.getLabel());
    		String s = md.getIcon();
    		if (s != null) {
     			Drawable d = null;
				TitaniumFileHelper tfh = new TitaniumFileHelper(tmm.getActivity());
				d = tfh.loadDrawable(s, true);
				if (d != null) {
					mi.setIcon(d);
				}
    		}

    		s = md.getCallback();
    		if (s != null) {
    			map.put(md.getItemId(), s);
    		}
    	} else {
    		throw new IllegalStateException("Unknown menu type expected: root, submenu, separator, or item");
    	}
    }


	public boolean dispatchOptionsItemSelected(MenuItem item) {
		boolean result = false;

		if (optionMenuCallbacks != null) {
			int id = item.getItemId();
			final String callback = optionMenuCallbacks.get(id);
			if (callback != null) {
				evalJS(callback);
				result = true;
			}
		}

		return result;
	}

	public ITitaniumLifecycle getLifecycle() {
		return this;
	}

	public View getNativeView() {
		return this;
	}

	// Lifecycle Methods

	public void onDestroy()
	{
		Log.e(LCAT, "ON DESTROY: " + getId());
		//Log.e(LCAT, "Loaded? " + loaded);

		tmm.onDestroy();
		if (/*loaded*/ true) {
			destroy();
		}
	}

	public void onPause() {
		tmm.onPause();
	}

	public void onResume() {
		tmm.onResume();
	}

}