/*
 * This code and all components (c) Copyright 2006 - 2016, Wowza Media Systems, LLC. All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
*/
package com.wowza.wms.plugin;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import com.wowza.util.StringUtils;
import com.wowza.wms.application.IApplicationInstance;
import com.wowza.wms.application.WMSProperties;
import com.wowza.wms.logging.WMSLogger;
import com.wowza.wms.logging.WMSLoggerFactory;
import com.wowza.wms.logging.WMSLoggerIDs;
import com.wowza.wms.module.ModuleBase;
import com.wowza.wms.stream.IMediaStream;
import com.wowza.wms.transcoder.model.ITranscoderFrameGrabResult;
import com.wowza.wms.transcoder.model.LiveStreamTranscoder;
import com.wowza.wms.transcoder.model.TranscoderNativeVideoFrame;
import com.wowza.wms.transcoder.model.TranscoderStream;
import com.wowza.wms.transcoder.model.TranscoderStreamSourceVideo;
import com.wowza.wms.transcoder.util.TranscoderStreamUtils;
 
public class TranscoderTimedSnapshot extends ModuleBase
{
	/**
	 * 
	 * FrameGrabResult takes the video frame from the decoded video, creates an RGB image from it and saves it to a file. 
	 *
	 */
	class FrameGrabResult implements ITranscoderFrameGrabResult
	{
	
		private final String streamName;
	
		FrameGrabResult(String streamName)
		{
			this.streamName = streamName;
		}
		
		public void onGrabFrame(TranscoderNativeVideoFrame videoFrame)
		{
	
			BufferedImage image = TranscoderStreamUtils.nativeImageToBufferedImage(videoFrame);
	
			if (image != null)
			{
				long time = System.currentTimeMillis() / 1000;
	
				String storageDir = appInstance.getStreamStoragePath();
	
				File file = new File(storageDir, imagePrefix + streamName + (timestampImages ? "_" + time : "") + "." + format);
	
				try
				{
					if (file.exists())
						file.delete();
					ImageIO.write(image, format, file);
				}
				catch (Exception e)
				{
					logger.error("ModuleTestTranscoderFrameGrab.grabFrame: File write error: " + file);
				}
			}
		}
	}

	/**
	 * 
	 * Snapshot Worker thread that calls takeSnapshot every interval.
	 *
	 */
	class SnapshotWorker extends Thread
		{
			private boolean running = true;
			private boolean quit = false;
			private final String streamName;
	
			SnapshotWorker(String streamName)
			{
				super("Transcoder Snapshot Worker ["+ appInstance.getContextStr() + "/" + streamName + "]");
				this.streamName = streamName;
			}
	
			synchronized boolean running()
			{
				return this.running;
			}
	
			synchronized void quit()
			{
				this.quit = true;
				notify();
			}
	
			public void run()
			{
				logger.info(this.getName() + " started.");
				while (true)
				{
					synchronized(this)
					{
						if (this.quit)
						{
							this.running = false;
							break;
						}
					}

					takeSnapshot(this.streamName);
					
					try
					{
						Thread.sleep(sleepTime);
					}
					catch (InterruptedException ie)
					{
					}
				}
			}
		}
	
	public static final String MODULE_NAME = "ModuleTranscoderTimedSnapshot";
	public static final String PROP_NAME_PREFIX = "transcoderTimedSnapshot";

	// list of worker threads.
	private List<SnapshotWorker> workers = new ArrayList<SnapshotWorker>();
	// Comma or pipe separated list of stream names to take snapshots from.
	private String streamNames = "myStream";
	// height & width of snapshot image.  If both are 0 then image is full size of source video frame.
	private int height = 0;
	private int width = 0;
	// interval between snapshots.  Minimum 1000ms (1 second)
	private int sleepTime = 1000;
	// Name to prefix images with.
	private String imagePrefix = "thumbnail_";
	// Image format.  Can be png, jpg or bmp.
	private String format = "png";
	// Create images with timestamp in name. If false then a single image is overwritten.
	private boolean timestampImages = true;
	
	private WMSLogger logger = null;
	private IApplicationInstance appInstance = null;

	/**
	 * Properties loaded from Application.xml and worker thread started for each stream name.
	 */
	public void onAppStart(IApplicationInstance appInstance)
	{
		this.appInstance = appInstance;
		this.logger = WMSLoggerFactory.getLoggerObj(appInstance);
		
		WMSProperties props = appInstance.getProperties();
		this.streamNames = props.getPropertyStr(PROP_NAME_PREFIX + "StreamNames", this.streamNames);
		this.height = props.getPropertyInt(PROP_NAME_PREFIX + "Height", this.height);
		this.width = props.getPropertyInt(PROP_NAME_PREFIX + "Width", this.width);
		this.sleepTime = props.getPropertyInt(PROP_NAME_PREFIX + "Interval", this.sleepTime);
		this.imagePrefix = props.getPropertyStr(PROP_NAME_PREFIX + "ImagePrefix", this.imagePrefix);
		this.format = props.getPropertyStr(PROP_NAME_PREFIX + "Format", this.format).toLowerCase();
		this.timestampImages = props.getPropertyBoolean(PROP_NAME_PREFIX + "TimestampImages", this.timestampImages);
		
		if (this.sleepTime < 1000)
		{
			this.logger.info(MODULE_NAME + " timer reset, less than 1000 , reset to 1000, remember it is in milliseconds", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
			this.sleepTime = 1000;
		}

		if (!this.format.equals("jpg") && !this.format.equals("png") && !this.format.equals("bmp"))
		{
			this.logger.info(MODULE_NAME + " format must be jpg, png or bmp , reset to png", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
			this.format = "png";
		}
		
		if(StringUtils.isEmpty(streamNames))
		{
			this.logger.info(MODULE_NAME + " Stream Names is empty, not doing anything.", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
		}
		else
		{
			String[] names = this.streamNames.replaceAll("(\\|)", ",").split(",");
			for (String name : names)
			{
				if(!StringUtils.isEmpty(name))
				{
					SnapshotWorker worker = new SnapshotWorker(name.trim());
					worker.setDaemon(true);
					worker.start();
					workers.add(worker);
				}
			}
		}
	}

	/**
	 * Worker threads shut down.
	 */
	public void onAppStop(IApplicationInstance appInstance)
	{

		for(SnapshotWorker worker : workers)
		{
			if(worker != null)
				worker.quit();
		}
		workers.clear();
	}
	
	/**
	 * 
	 * @param streamName - Name of stream to take the snapshot from.<br>
	 * Called from SnapshotWorker.run() every interval.
	 */
	public void takeSnapshot(String streamName)
	{
		try
		{
			while (true)
			{
				IMediaStream stream = appInstance.getStreams().getStream(streamName);
				if (stream == null)
					break;

				LiveStreamTranscoder liveStreamTranscoder = (LiveStreamTranscoder)stream.getLiveStreamTranscoder("transcoder");
				if (liveStreamTranscoder == null)
					break;

				TranscoderStream transcoderStream = liveStreamTranscoder.getTranscodingStream();
				if (transcoderStream == null)
					break;

				TranscoderStreamSourceVideo sourceVideo = transcoderStream.getSource().getVideo();
				if (sourceVideo == null)
					break;
				
				if (width > 0 && height > 0)
					sourceVideo.grabFrame(new FrameGrabResult(streamName), width, height);
				else
					sourceVideo.grabFrame(new FrameGrabResult(streamName));
				break;
			}
		}
		catch (Exception e)
		{
			logger.error(MODULE_NAME + " Exception: [" + appInstance.getContextStr() + "/" + streamName + "] " + e.toString(), e);
		}
	}
}
