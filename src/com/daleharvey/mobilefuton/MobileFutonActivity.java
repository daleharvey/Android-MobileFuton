package com.daleharvey.mobilefuton;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.ServiceConnection;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.couchbase.android.CouchbaseMobile;
import com.couchbase.android.ICouchbaseDelegate;

public class MobileFutonActivity extends Activity {

	private final MobileFutonActivity self = this;
	protected static final String TAG = "CouchAppActivity";

	private ServiceConnection couchServiceConnection;
	private ProgressDialog installProgress;
	private WebView webView;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		startCouch();
	}

	@Override
	public void onRestart() {
		super.onRestart();
		startCouch();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		try {
			unbindService(couchServiceConnection);
		} catch (IllegalArgumentException e) {
		}
	}

	private final ICouchbaseDelegate mCallback = new ICouchbaseDelegate.Stub() {
		@Override
		public void couchbaseStarted(String host, int port) {

			if (installProgress != null) {
				installProgress.dismiss();
			}

			String url = "http://" + host + ":" + Integer.toString(port) + "/";
		    String ip = getLocalIpAddress();
		    String param = (ip == null) ? "" : "?ip=" + ip;

			ensureDesignDoc("mobilefuton", url);
			launchFuton(url + "mobilefuton/_design/mobilefuton/index.html" + param);
		}

		@Override
		public void installing(int completed, int total) {
			ensureProgressDialog();
			installProgress.setTitle("Installing");
			installProgress.setProgress(completed);
			installProgress.setMax(total);
		}

		@Override
		public void exit(String error) {
			Log.v(TAG, error);
			couchError();
		}
	};

	private void ensureProgressDialog() {
		if (installProgress == null) {
			installProgress = new ProgressDialog(MobileFutonActivity.this);
			installProgress.setTitle(" ");
			installProgress.setCancelable(false);
			installProgress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
			installProgress.show();
		}
	}

	private void startCouch() {
		CouchbaseMobile couch = new CouchbaseMobile(getBaseContext(), mCallback);

		try {
			couch.copyIniFile("mobilefuton.ini");
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		couchServiceConnection = couch.startCouchbase();
	}

	private void couchError() {
		AlertDialog.Builder builder = new AlertDialog.Builder(self);
		builder.setMessage("Unknown Error")
				.setPositiveButton("Try Again?",
						new DialogInterface.OnClickListener() {
							@Override
							public void onClick(DialogInterface dialog, int id) {
								startCouch();
							}
						})
				.setNegativeButton("Cancel",
						new DialogInterface.OnClickListener() {
							@Override
							public void onClick(DialogInterface dialog, int id) {
								self.moveTaskToBack(true);
							}
						});
		AlertDialog alert = builder.create();
		alert.show();
	}

	private void launchFuton(String url) {
		webView = new WebView(MobileFutonActivity.this);
		webView.setWebChromeClient(new WebChromeClient());
		webView.setWebViewClient(new CustomWebViewClient());
		webView.getSettings().setJavaScriptEnabled(true);
		webView.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
		webView.getSettings().setDomStorageEnabled(true);

		webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);

		webView.requestFocus(View.FOCUS_DOWN);
	    webView.setOnTouchListener(new View.OnTouchListener() {
	        @Override
	        public boolean onTouch(View v, MotionEvent event) {
	            switch (event.getAction()) {
	                case MotionEvent.ACTION_DOWN:
	                case MotionEvent.ACTION_UP:
	                    if (!v.hasFocus()) {
	                        v.requestFocus();
	                    }
	                    break;
	            }
	            return false;
	        }
	    });

		setContentView(webView);
		webView.loadUrl(url);
	};

	private class CustomWebViewClient extends WebViewClient {
		@Override
		public boolean shouldOverrideUrlLoading(WebView view, String url) {
			view.loadUrl(url);
			return true;
		}
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
	    if ((keyCode == KeyEvent.KEYCODE_BACK) && webView.canGoBack()) {
	    	webView.goBack();
	        return true;
	    }
	    return super.onKeyDown(keyCode, event);
	}

	public String getLocalIpAddress() {
		try {
			for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
				NetworkInterface intf = en.nextElement();
				for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
					InetAddress inetAddress = enumIpAddr.nextElement();
					if (!inetAddress.isLoopbackAddress()) {
						return inetAddress.getHostAddress().toString();
					}
				}
			}
		} catch (SocketException ex) {
			ex.printStackTrace();
		}
		return null;
	}

	/*
	* Will check for the existence of a design doc and if it doesnt exist,
	* upload the json found at dataPath to create it
	*/
	private void ensureDesignDoc(String dbName, String url) {

		try {

			File hashCache = new File(CouchbaseMobile.dataPath() + "/couchapps/" + dbName + ".couchapphash");
			String data = readAsset(getAssets(), dbName + ".json");
			String md5 = md5(data);
			String cachedHash;
			Boolean toUpdate;

			try {
				cachedHash = readFile(hashCache);
				toUpdate = !md5.equals(cachedHash);
			} catch (Exception e) {
				e.printStackTrace();
				toUpdate = true;
			}

			if (toUpdate == true) {

				String ddocUrl = url + dbName + "/_design/" + dbName;

				AndCouch req = AndCouch.get(ddocUrl);

				if (req.status == 404) {
					Log.v(TAG, "Installing Couchapp");
					AndCouch.put(url + dbName, null);
					AndCouch.put(ddocUrl, data);
				} else if (req.status == 200) {
					Log.v(TAG, "Couchapp Found, Updating");
					String rev = req.json.getString("_rev");
					JSONObject json = new JSONObject(data);
					json.put("_rev", rev);
					AndCouch.put(url + dbName, null);
					AndCouch.put(ddocUrl, json.toString());
				}

				new File(hashCache.getParent()).mkdirs();
				writeFile(hashCache, md5);

			} else {
				Log.v(TAG, "Couchapp up to date");
			}

		} catch (IOException e) {
			e.printStackTrace();
			// There is no design doc to load
		} catch (JSONException e) {
			e.printStackTrace();
		}
	};

	public static String readAsset(AssetManager assets, String path) throws IOException {
		InputStream is = assets.open(path);
		int size = is.available();
		byte[] buffer = new byte[size];
		is.read(buffer);
		is.close();
		return new String(buffer);
	}

    public static String md5(String input){
        String res = "";
        try {
            MessageDigest algorithm = MessageDigest.getInstance("MD5");
            algorithm.reset();
            algorithm.update(input.getBytes());
            byte[] md5 = algorithm.digest();
            String tmp = "";
            for (int i = 0; i < md5.length; i++) {
                tmp = (Integer.toHexString(0xFF & md5[i]));
                if (tmp.length() == 1) {
                    res += "0" + tmp;
                } else {
                    res += tmp;
                }
            }
        } catch (NoSuchAlgorithmException ex) {}
        return res;
    }

    public static String readFile(File file) throws IOException {
        StringBuffer fileData = new StringBuffer(1000);
        BufferedReader reader = new BufferedReader(new FileReader(file));
        char[] buf = new char[1024];
        int numRead=0;
        while((numRead=reader.read(buf)) != -1){
            String readData = String.valueOf(buf, 0, numRead);
            fileData.append(readData);
            buf = new char[1024];
        }
        reader.close();
        return fileData.toString();
    }

    public static void writeFile(File file, String data) throws IOException {
    	FileWriter fstream = new FileWriter(file);
    	BufferedWriter out = new BufferedWriter(fstream);
    	out.write(data);
    	out.close();
    }
}