package org.openni.android.tools.niviewer;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.hardware.usb.UsbDeviceConnection;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.LinearLayout;

import org.openni.Device;
import org.openni.DeviceInfo;
import org.openni.OpenNI;
import org.openni.Recorder;
import org.openni.android.OpenNIHelper;

public class NiViewerActivity 
		extends Activity 
		implements OpenNIHelper.DeviceOpenListener {
	
	private static final String TAG = "NiViewer";
	private OpenNIHelper mOpenNIHelper;
	private UsbDeviceConnection mDeviceConnection;
	private boolean mDeviceOpenPending = false;
	private Device mDevice;
	private Recorder mRecorder;
	private String mRecordingName;
	private String mRecording;
	private LinearLayout mStreamsContainer;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		mOpenNIHelper = new OpenNIHelper(this);
		OpenNI.setLogAndroidOutput(true);
		OpenNI.setLogMinSeverity(0);
		OpenNI.initialize();
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_niviewer);
		mStreamsContainer = (LinearLayout)findViewById(R.id.streams_container);
		if (Configuration.ORIENTATION_PORTRAIT == getResources().getConfiguration().orientation) {
			mStreamsContainer.setOrientation(LinearLayout.VERTICAL);
		} else {
			mStreamsContainer.setOrientation(LinearLayout.HORIZONTAL);
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.niviewer_menu, menu);
		return true;
	}
	
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case R.id.record:
				toggleRecording(item);
				return true;
			case R.id.add_stream:
				addStream();
				return true;
			default:
				return super.onOptionsItemSelected(item);
		}
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		
		mOpenNIHelper.shutdown();
		OpenNI.shutdown();
	}
	
	@Override 
	protected void onStart() {
		Log.d(TAG, "onStart");
		super.onStart();
		
		final android.content.Intent intent = getIntent ();

		if (intent != null) {
			final android.net.Uri data = intent.getData ();
			if (data != null) {
				mRecording = data.getEncodedPath ();
				Log.d(TAG, "Will open file " + mRecording);
			}
		}
	}

	@Override
	protected void onResume() {
		Log.d(TAG, "onResume");

		super.onResume();

		// onResume() is called after the USB permission dialog is closed, in which case, we don't want
		// to request device permissions again
		if (mDeviceOpenPending) {
			return;
		}

		// Request opening the first OpenNI-compliant device found
		String uri;
		
		if (mRecording != null) {
			uri = mRecording;
		} else {
			List<DeviceInfo> devices = OpenNI.enumerateDevices();
			if (devices.isEmpty()) {
				showAlertAndExit("No OpenNI-compliant device found.");
				return;
			}
			uri = devices.get(0).getUri();
		}
		
		mDeviceOpenPending = true;
		mOpenNIHelper.requestDeviceOpen(uri, this);
	}
	
	private void showAlert(String message) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setMessage(message);
		builder.show();
	}
	
	private void showAlertAndExit(String message) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setMessage(message);
		builder.setNeutralButton("OK", new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int which) {
				finish();
			}
		});
		builder.show();
	}

	@Override
	public void onDeviceOpened(Device aDevice) {
		Log.d(TAG, "Permission granted for device " + aDevice.getDeviceInfo().getUri());
		
		mDeviceOpenPending = false;

		mDevice = aDevice;
		for (StreamView streamView : getStreamViews()) {
			streamView.setDevice(mDevice);
		}
		
		mStreamsContainer.requestLayout();
		addStream();
	}
	
	private void addStream() {
		StreamView streamView = new StreamView(this);
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		
		if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
			params.height = 0;
		} else {
			params.width = 0;
		}
		
		params.weight = 1;
		params.gravity = Gravity.CENTER;
		streamView.setLayoutParams(params);
		
		streamView.setDevice(mDevice);
		mStreamsContainer.addView(streamView);
		mStreamsContainer.requestLayout();
	}

	@SuppressLint("SimpleDateFormat")
	private void toggleRecording(MenuItem item) {
		if (mRecorder == null) {
			mRecordingName = Environment.getExternalStorageDirectory().getPath() +
					"/" + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date()) + ".oni";
			
			try {
				mRecorder = Recorder.create(mRecordingName);
				for (StreamView streamView : getStreamViews()) {
					mRecorder.addStream(streamView.getStream(), true);
				}
				mRecorder.start();
			} catch (RuntimeException ex) {
				mRecorder = null;
				showAlert("Failed to start recording: " + ex.getMessage());
				return;
			}
			
			item.setTitle(R.string.stop_record);
		} else {
			stopRecording();
			item.setTitle(R.string.start_record);
		}
	}
	
	private void stopRecording() {
		if (mRecorder != null) {
			mRecorder.stop();
			mRecorder.destroy();
			mRecorder = null;

			showAlert("Recording saved to: " + mRecordingName);
			mRecordingName = null;
		}
	}
	
	@Override
	public void onDeviceOpenFailed(String uri) {
		Log.e(TAG, "Failed to open device " + uri);
		mDeviceOpenPending = false;
		showAlertAndExit("Failed to open device");
	}

	@Override
	protected void onPause() {
		Log.d(TAG, "onPause");

		super.onPause();
		
		// onPause() is called just before the USB permission dialog is opened, in which case, we don't
		// want to shutdown OpenNI
		if (mDeviceOpenPending)
			return;

		stopRecording();

		for (StreamView streamView : getStreamViews()) {
			streamView.stop();
		}
		
		if (mDevice != null) {
			mDevice.close();
			mDevice = null;
		}

		if (mDeviceConnection != null) {
			mDeviceConnection.close();
			mDeviceConnection = null;
		}
	}
	
	private List<StreamView> getStreamViews() {
		int count = mStreamsContainer.getChildCount();
		ArrayList<StreamView> list = new ArrayList<StreamView>(count);
		for (int i = 0; i < count; ++i) {
			StreamView view = (StreamView)mStreamsContainer.getChildAt(i);
			list.add(view);
		}
		return list;
	}
}