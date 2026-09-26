package dev.perf.chat.queue;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class DispatchFailedException extends ErrorResponseException {

	public DispatchFailedException(Throwable cause) {
		super(HttpStatus.SERVICE_UNAVAILABLE,
				ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Fila indisponível, tente novamente"),
				cause);
	}

}
