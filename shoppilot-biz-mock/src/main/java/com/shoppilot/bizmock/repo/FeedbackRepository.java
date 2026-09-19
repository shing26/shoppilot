package com.shoppilot.bizmock.repo;

import com.shoppilot.bizmock.domain.Feedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FeedbackRepository extends JpaRepository<Feedback, String> {

    List<Feedback> findAllByOrderByCreatedAtDesc();

    List<Feedback> findByReviewStatusOrderByCreatedAtDesc(String reviewStatus);
}
