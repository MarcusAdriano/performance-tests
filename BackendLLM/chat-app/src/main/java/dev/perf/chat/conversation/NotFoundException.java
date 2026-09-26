package dev.perf.chat.conversation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class NotFoundException extends ErrorResponseException {

	public NotFoundException(String detail) {
		super(HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detail), null);
	}

}
