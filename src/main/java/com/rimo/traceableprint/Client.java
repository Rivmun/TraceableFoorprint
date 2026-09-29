package com.rimo.traceableprint;

//~ if neoforge 'fabric' -> 'neoforge'
import com.rimo.traceableprint.loaders.fabric.Platform;

public class Client {
	// 客户端侧节流：上次响应邀约回传配置的时间戳(ms)，防恶意服务端反复邀约
	private static final long CLIENT_SEND_THROTTLE_MS = 500L;
	private static volatile long lastClientSendMs = 0L;

	public static void init() {
		//
	}

	/**
	 * 客户端收到服务端 S2C 邀约 {@link Common.UploadRequestPayload} 后：
	 * 把本地 {@link Common#CONFIG} 序列化并经 {@link Platform#sendToServer} 回传（带节流）。
	 * 真正的上传动作刻意藏在这里——只有被 /upload 邀约过的客户端才会回包。
	 */
	public static void handleUploadRequestPayload() {
		long now = System.currentTimeMillis();
		if (now - lastClientSendMs < CLIENT_SEND_THROTTLE_MS) {
			return; // 忽略短时间内的重复邀约
		}
		lastClientSendMs = now;
		Platform.sendToServer(new Common.UploadConfigPayload(Common.CONFIG.serializeJson()));
	}
}
