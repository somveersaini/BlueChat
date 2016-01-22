package com.iblue.chat.xmpp.jingle;

import com.iblue.chat.entities.DownloadableFile;

public interface OnFileTransmissionStatusChanged {
	void onFileTransmitted(DownloadableFile file);

	void onFileTransferAborted();
}
