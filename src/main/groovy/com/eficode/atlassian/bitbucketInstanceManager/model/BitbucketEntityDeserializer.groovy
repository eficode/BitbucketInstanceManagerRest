package com.eficode.atlassian.bitbucketInstanceManager.model

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.BeanProperty
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.deser.ContextualDeserializer
import com.fasterxml.jackson.databind.deser.std.StdDeserializer

class BitbucketEntityDeserializer extends StdDeserializer<BitbucketEntity>{

    protected BitbucketEntityDeserializer(Class<?> vc) {
        super(vc)
    }

    Class getDestinationClass() {
        BitbucketEntity
    }

    @Override
    BitbucketEntity deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException, JacksonException {
        deserializationContext.readValue(jsonParser, destinationClass)
        return null
    }
}
