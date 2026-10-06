package net.java21.blog.backend.common;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.api.PageRequests;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 공통 응답·예외 처리 확인용 컨트롤러(테스트 전용). */
@TestComponent
@RestController
@RequestMapping("/api/v1/test")
class CommonResponseTestController {

    record Item(long id, String title) {
    }

    record CreateRequest(
            @NotBlank @Size(min = 2, max = 5) String title,
            @Min(1) @Max(10) int count,
            @Email String email) {
    }

    @GetMapping("/items/{id}")
    ApiResponse<Item> get(@PathVariable long id) {
        if (id == 404) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Item not found: " + id);
        }
        if (id == 500) {
            throw new IllegalStateException("boom");
        }
        return ApiResponse.ok(new Item(id, "title " + id));
    }

    @GetMapping("/items")
    ApiResponse<List<Item>> page(@RequestParam int page, @RequestParam(defaultValue = "2") @Max(50) int size) {
        List<Item> items = List.of(new Item(1, "a"), new Item(2, "b"));
        return ApiResponse.page(new PageImpl<>(items, PageRequest.of(page, size), 135));
    }

    private static final PageRequests ITEM_PAGES = PageRequests.sortableBy(Sort.by(Sort.Direction.DESC, "id"), "id", "title");

    /** {@link PageRequests} 사용 예: page·size·sort를 해석하고, 허용하지 않은 sort는 400 VALIDATION_FAILED. */
    @GetMapping("/paged")
    ApiResponse<List<Item>> paged(@RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        Pageable pageable = ITEM_PAGES.resolve(page, size, sort);
        String summary = "page=" + pageable.getPageNumber() + " size=" + pageable.getPageSize() + " sort=" + pageable.getSort();
        return ApiResponse.page(new PageImpl<>(List.of(new Item(1, summary)), pageable, 135));
    }

    @GetMapping("/feed")
    ApiResponse<List<Item>> feed(@RequestParam(required = false) String cursor) {
        return ApiResponse.cursor(List.of(new Item(3, "c")), cursor == null ? "next-1" : null);
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<Item> create(@Valid @RequestBody CreateRequest request) {
        return ApiResponse.ok(new Item(1, request.title()));
    }

    @PostMapping("/rule")
    ApiResponse<Void> rule() {
        throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Rule broken",
                List.of(FieldError.of("handle", "HANDLE_RESERVED")));
    }

    @DeleteMapping("/items/{id}")
    ApiResponse<Void> delete(@PathVariable long id) {
        return ApiResponse.ok();
    }
}
