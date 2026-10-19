import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitDescribeVersionTest {

    @Test
    fun `a tagged commit is the bare release version`() {
        assertEquals("0.2.5", versionFromGitDescribe("v0.2.5\n"))
    }

    @Test
    fun `commits past a tag keep the distance and abbreviated hash`() {
        assertEquals("0.2.5-5-gb9361840", versionFromGitDescribe("v0.2.5-5-gb9361840\n"))
    }

    @Test
    fun `output that is not a version is rejected`() {
        assertNull(versionFromGitDescribe(""))
        assertNull(versionFromGitDescribe("  \n"))
        assertNull(versionFromGitDescribe("vnext"))
    }
}
