package dev.cloudlite.iam.service;

import dev.cloudlite.iam.domain.User;

public record NewUser(User user, String apiKey) {
}
