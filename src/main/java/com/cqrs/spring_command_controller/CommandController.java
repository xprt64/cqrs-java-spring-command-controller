package com.cqrs.spring_command_controller;

import com.cqrs.aggregates.AggregateCommandHandlingException;
import com.cqrs.base.Command;
import com.cqrs.commands.AnnotatedCommandSubscriberMap;
import com.cqrs.commands.CommandDispatcher;
import com.cqrs.events.EventWithMetaData;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/commands")
public class CommandController {

    private final CommandDispatcher commandDispatcher;
    private final AnnotatedCommandSubscriberMap annotatedCommandSubscriberMap;
    private final ObjectMapper objectMapper;
    private final ObjectMapper serializer;

    public CommandController(
        CommandDispatcher commandDispatcher,
        AnnotatedCommandSubscriberMap annotatedCommandSubscriberMap,
        ObjectMapper objectMapper
    ) {
        this.commandDispatcher = commandDispatcher;
        this.annotatedCommandSubscriberMap = annotatedCommandSubscriberMap;
        this.objectMapper = objectMapper;
        this.serializer = serializer();
    }

    @PostMapping("/dispatch")
    public void dispatch(@RequestBody Body requestBody) throws ExceptionCaught {
        if (annotatedCommandSubscriberMap.getMap().get(requestBody.type) == null) {
            throw new InvalidParameterException("Command class not valid");
        }
        dispatchCommand(requestBody).toArray(new EventWithMetaData[0]);
    }

    private List<EventWithMetaData> dispatchCommand(Body requestBody) throws ExceptionCaught {
        try {
            System.out.println("dispatching command " + requestBody.type);
            return commandDispatcher.dispatchCommand((Command) objectMapper.readValue(requestBody.payload, Class.forName(requestBody.type)));
        } catch (AggregateCommandHandlingException e) {
            e.printStackTrace();
            throw new ExceptionCaught(HttpStatus.INTERNAL_SERVER_ERROR, e.getCause());
        } catch (Throwable e) {
            e.printStackTrace();
            throw new ExceptionCaught(HttpStatus.INTERNAL_SERVER_ERROR, e);
        }
    }

    @ExceptionHandler(ExceptionCaught.class)
    public ResponseEntity<Error> error(ExceptionCaught ex) {
        return new ResponseEntity<Error>(
            new Error(ex.getCause().getClass().getCanonicalName(), ex.getCause().getMessage()),
            ex.code
        );
    }

    @PostMapping("/dispatchAndReturnEvents")
    public void dispatchAndReturnEvents(@RequestBody Body requestBody, HttpServletResponse response) throws IOException, ExceptionCaught {
        if (annotatedCommandSubscriberMap.getMap().get(requestBody.type) == null) {
            throw new InvalidParameterException("Command class not valid");
        }
        List<EventWithMetaData> events = dispatchCommand(requestBody);
        List<String> rsp = new ArrayList<>();

        for (EventWithMetaData event : events) {
            rsp.add(serializer.writeValueAsString(event));
        }
        String jsonResponse = '[' + String.join(",", rsp) + ']';
        response.setContentType("application/json");
        try {
            response.getOutputStream().write(jsonResponse.getBytes());
        } finally {
            response.getOutputStream().close();
        }
    }

    private ObjectMapper serializer() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        mapper.registerModule(new ParameterNamesModule(JsonCreator.Mode.PROPERTIES));
        mapper.findAndRegisterModules();
        mapper.enableDefaultTyping(ObjectMapper.DefaultTyping.EVERYTHING, JsonTypeInfo.As.PROPERTY);
        return mapper;
    }

    public static class Body {
        public String payload;
        public String type;
    }

    private static class Error {
        public String type;
        public String message;

        public Error(String type, String message) {
            this.type = type;
            this.message = message;
        }
    }

    private static class ExceptionCaught extends Exception {
        private HttpStatus code;

        ExceptionCaught(HttpStatus code, Throwable cause) {
            super(cause);
            this.code = code;
        }
    }
}
