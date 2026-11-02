package com.recommend.event;

/** Published after a material is created, updated, reviewed or deleted. */
public record MaterialIndexEvent(Long materialId) {
}
