package com.claudecode.ui.lanterna.status;

import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.GoalStatusAttachment;
import com.claudecode.core.message.Message;

import java.util.List;


public final class GoalStatusHistory {

    private GoalStatusHistory() { }

    public static GoalStatusAttachment latestSuccessful(List<Message> messages) {
        if (messages == null) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (!(message instanceof AttachmentMessage attachment)
                    || !(attachment.payload() instanceof GoalStatusAttachment status)) {
                continue;
            }
            if (!status.hasSentinelMarker() && status.met()) return status;
        }
        return null;
    }
}
