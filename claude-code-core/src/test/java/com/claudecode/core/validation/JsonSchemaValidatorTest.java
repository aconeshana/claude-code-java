package com.claudecode.core.validation;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonSchemaValidatorTest {

    @Test
    void anyOfAndConstAcceptExactlyOneReleased197TaskStatusBranch() {
        ObjectNode schema = JsonUtils.getMapper().createObjectNode();
        var anyOf = schema.putArray("anyOf");
        anyOf.addObject().put("type", "string").putArray("enum")
            .add("pending").add("in_progress").add("completed");
        anyOf.addObject().put("type", "string").put("const", "deleted");

        JsonSchemaValidator validator = new JsonSchemaValidator();

        assertTrue(validator.validateAgainstJsonSchema(
            JsonUtils.getMapper().getNodeFactory().textNode("pending"), schema).isSuccess());
        assertTrue(validator.validateAgainstJsonSchema(
            JsonUtils.getMapper().getNodeFactory().textNode("deleted"), schema).isSuccess());
        assertFalse(validator.validateAgainstJsonSchema(
            JsonUtils.getMapper().getNodeFactory().textNode("bogus"), schema).isSuccess());
    }
}
