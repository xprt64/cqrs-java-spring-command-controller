package com.cqrs.spring_command_controller;

import com.cqrs.aggregates.AggregateCommandHandlingException;
import com.cqrs.base.Command;
import com.cqrs.commands.AnnotatedCommandSubscriberMap;
import com.cqrs.commands.CommandDispatcher;
import com.cqrs.commands.CommandRejectedByValidators;
import com.cqrs.events.EventWithMetaData;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/commands")
public class CommandController {

    private final CommandDispatcher commandDispatcher;
    private final AnnotatedCommandSubscriberMap annotatedCommandSubscriberMap;
    private final ObjectMapper fontendDeserializer;
    private final ObjectMapper serializer;

    public CommandController(
        CommandDispatcher commandDispatcher,
        AnnotatedCommandSubscriberMap annotatedCommandSubscriberMap
    ) {
        this.commandDispatcher = commandDispatcher;
        this.annotatedCommandSubscriberMap = annotatedCommandSubscriberMap;
        this.fontendDeserializer = frontendDeserializer();
        this.serializer = serializer();
    }

    @PostMapping("/dispatch")
    @CrossOrigin
    public void dispatch(@RequestBody Body requestBody, HttpServletResponse response) throws ExceptionCaught {
        if (annotatedCommandSubscriberMap.getMap().get(requestBody.type) == null) {
            throw new InvalidParameterException("Command class not valid");
        }
        dispatchCommand(requestBody);
    }

    @CrossOrigin
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

    private List<EventWithMetaData> dispatchCommand(Body requestBody) throws ExceptionCaught {
        try {
            System.out.println("dispatching command " + requestBody.type);
            List<EventWithMetaData> result = commandDispatcher.dispatchCommand(
                (Command) fontendDeserializer.readValue(requestBody.payload, Class.forName(requestBody.type)));
            System.out.println("command dispatched, events: " + result.size());
            return result;
        } catch (AggregateCommandHandlingException e) {
            e.getCause().printStackTrace();
            throw new ExceptionCaught(HttpStatus.INTERNAL_SERVER_ERROR, e.getCause());
        }  catch (CommandRejectedByValidators e) {
            throw new ExceptionCaught(HttpStatus.INTERNAL_SERVER_ERROR, e);
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

    @ExceptionHandler(CommandRejectedByValidators.class)
    public ResponseEntity<List<Error>> error(CommandRejectedByValidators ex) {
        return new ResponseEntity<>(
            ex.getErrors().stream().map(e -> new Error(e.getClass().getCanonicalName(), e.getMessage())).collect(Collectors.toList()),
            HttpStatus.INTERNAL_SERVER_ERROR
        );
    }

    private ObjectMapper serializer() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        mapper.registerModule(new ParameterNamesModule(JsonCreator.Mode.PROPERTIES));
        mapper.findAndRegisterModules();
        mapper.enableDefaultTyping(ObjectMapper.DefaultTyping.EVERYTHING, JsonTypeInfo.As.PROPERTY);
        return mapper;
    }

    private ObjectMapper frontendDeserializer() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        mapper.registerModule(new ParameterNamesModule(JsonCreator.Mode.PROPERTIES));
        mapper.findAndRegisterModules();
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
