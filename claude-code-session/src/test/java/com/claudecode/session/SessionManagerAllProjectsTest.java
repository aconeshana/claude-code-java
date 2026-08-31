package com.claudecode.session;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;


class SessionManagerAllProjectsTest {

    @TempDir Path base;

    private String writeSession(String cwd, boolean withCwdField) throws Exception {
        SessionManager mgr = new SessionManager(base, cwd);
        SessionStorage storage = new SessionStorage();
        String id = mgr.createSession();
        if (withCwdField) {
            // appendMessage 6 参重载 stamp cwd（同 /branch 用法）
            storage.appendMessage(mgr.getSessionFile(id),
                new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hi")),
                id, null, false, null);
            // 手动补 cwd 字段（appendMessage 的 cwd stamp 依赖运行时 wiring）
            String line = Files.readString(mgr.getSessionFile(id));
            if (!Strings.CS.contains(line, "\"cwd\"")) {
                line = line.replaceFirst("\\{", "{\"cwd\":\"" + cwd + "\",");
                Files.writeString(mgr.getSessionFile(id), line);
            }
        } else {
            storage.appendMessage(mgr.getSessionFile(id),
                new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hi")));
        }
        return id;
    }

    @Test
    void listsAcrossProjectDirsNewestFirst() throws Exception {
        String idA = writeSession("/proj/a", true);
        Thread.sleep(20);
        String idB = writeSession("/proj/b", true);

        SessionManager fromA = new SessionManager(base, "/proj/a");
        List<SessionInfo> all = fromA.listAllProjectsSessions(10);

        assertEquals(2, all.size(), "sessions from both project dirs listed");
        assertEquals(idB, all.getFirst().id(), "newest (B) first");
        assertEquals(idA, all.get(1).id());
    }

    @Test
    void foreignSessionWithoutCwdIsSkipped_ownIsKept() throws Exception {
        String own = writeSession("/proj/a", false);  // 自己目录（cwd 有无均保留）

        // 外部目录、裸写不带 cwd 字段的转录（SessionStorage 运行时会自动 stamp
        // user.dir，所以必须绕过它构造这个边缘态）
        String foreign = UUID.randomUUID().toString();
        Path foreignDir = new SessionManager(base, "/proj/b").getSessionFile(foreign).getParent();
        Files.createDirectories(foreignDir);
        Files.writeString(foreignDir.resolve(foreign + ".jsonl"),
            "{\"type\":\"user\",\"uuid\":\"" + UUID.randomUUID()
            + "\",\"timestamp\":\"2026-07-01T00:00:00.000Z\",\"isSidechain\":false,"
            + "\"message\":{\"role\":\"user\",\"content\":\"hi\"}}\n");

        SessionManager fromA = new SessionManager(base, "/proj/a");
        List<SessionInfo> all = fromA.listAllProjectsSessions(10);

        assertTrue(all.stream().anyMatch(s -> s.id().equals(own)));
        assertTrue(all.stream().noneMatch(s -> s.id().equals(foreign)),
            "cross-project session without recorded cwd is unusable — skipped");
    }

    @Test
    void missingProjectsRootYieldsEmpty() {
        assertTrue(new SessionManager(base.resolve("void"), "/x")
            .listAllProjectsSessions(5).isEmpty());
    }
}
