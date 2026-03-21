package com.effectivedisco.controller.web;

import com.effectivedisco.dto.request.CommentRequest;
import com.effectivedisco.dto.request.PostRequest;
import com.effectivedisco.dto.response.PostResponse;
import com.effectivedisco.service.CommentService;
import com.effectivedisco.service.PostService;
import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.Set;

@Controller
@RequiredArgsConstructor
public class BoardWebController {

    private final PostService postService;
    private final CommentService commentService;

    @GetMapping("/")
    public String index(@RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "20") int size,
                        @RequestParam(required = false) String keyword,
                        @RequestParam(required = false) String tag,
                        Model model) {
        model.addAttribute("posts", postService.getPosts(page, size, keyword, tag));
        model.addAttribute("keyword", keyword != null ? keyword : "");
        model.addAttribute("tag", tag != null ? tag : "");
        model.addAttribute("allTags", postService.getAllTagNames());
        return "index";
    }

    @GetMapping("/posts/{id}")
    public String postDetail(@PathVariable Long id, Model model,
                             @AuthenticationPrincipal UserDetails userDetails,
                             HttpSession session) {
        @SuppressWarnings("unchecked")
        Set<Long> viewed = (Set<Long>) session.getAttribute("viewedPosts");
        if (viewed == null) {
            viewed = new HashSet<>();
            session.setAttribute("viewedPosts", viewed);
        }
        if (viewed.add(id)) {
            postService.incrementViewCount(id);
        }
        model.addAttribute("post", postService.getPost(id));
        model.addAttribute("comments", commentService.getComments(id));
        model.addAttribute("commentRequest", new CommentRequest());
        boolean liked = userDetails != null && postService.isLikedByUser(id, userDetails.getUsername());
        model.addAttribute("liked", liked);
        return "post/detail";
    }

    @PostMapping("/posts/{id}/like")
    public String toggleLike(@PathVariable Long id,
                             @AuthenticationPrincipal UserDetails userDetails) {
        postService.toggleLike(id, userDetails.getUsername());
        return "redirect:/posts/" + id;
    }

    @GetMapping("/posts/new")
    public String newPostForm(Model model) {
        model.addAttribute("postRequest", new PostRequest());
        model.addAttribute("isEdit", false);
        return "post/form";
    }

    @PostMapping("/posts")
    public String createPost(@ModelAttribute PostRequest postRequest,
                             @AuthenticationPrincipal UserDetails userDetails) {
        PostResponse post = postService.createPost(postRequest, userDetails.getUsername());
        return "redirect:/posts/" + post.getId();
    }

    @GetMapping("/posts/{id}/edit")
    public String editPostForm(@PathVariable Long id, Model model,
                               @AuthenticationPrincipal UserDetails userDetails) {
        PostResponse post = postService.getPost(id);
        if (!post.getAuthor().equals(userDetails.getUsername())) {
            return "redirect:/posts/" + id;
        }
        PostRequest postRequest = new PostRequest();
        postRequest.setTitle(post.getTitle());
        postRequest.setContent(post.getContent());
        postRequest.setTagsInput(String.join(", ", post.getTags()));
        model.addAttribute("postRequest", postRequest);
        model.addAttribute("postId", id);
        model.addAttribute("isEdit", true);
        return "post/form";
    }

    @PostMapping("/posts/{id}/edit")
    public String updatePost(@PathVariable Long id,
                             @ModelAttribute PostRequest postRequest,
                             @AuthenticationPrincipal UserDetails userDetails) {
        postService.updatePost(id, postRequest, userDetails.getUsername());
        return "redirect:/posts/" + id;
    }

    @PostMapping("/posts/{id}/delete")
    public String deletePost(@PathVariable Long id,
                             @AuthenticationPrincipal UserDetails userDetails) {
        postService.deletePost(id, userDetails.getUsername());
        return "redirect:/";
    }

    @PostMapping("/posts/{postId}/comments")
    public String addComment(@PathVariable Long postId,
                             @ModelAttribute CommentRequest commentRequest,
                             @AuthenticationPrincipal UserDetails userDetails) {
        commentService.createComment(postId, commentRequest, userDetails.getUsername());
        return "redirect:/posts/" + postId + "#comments";
    }

    @PostMapping("/posts/{postId}/comments/{id}/replies")
    public String addReply(@PathVariable Long postId,
                           @PathVariable Long id,
                           @ModelAttribute CommentRequest commentRequest,
                           @AuthenticationPrincipal UserDetails userDetails) {
        commentService.createReply(postId, id, commentRequest, userDetails.getUsername());
        return "redirect:/posts/" + postId + "#comment-" + id;
    }

    @PostMapping("/posts/{postId}/comments/{id}/delete")
    public String deleteComment(@PathVariable Long postId,
                                @PathVariable Long id,
                                @AuthenticationPrincipal UserDetails userDetails) {
        commentService.deleteComment(postId, id, userDetails.getUsername());
        return "redirect:/posts/" + postId + "#comments";
    }
}
