package dev.sieve.core.model;

import java.util.Objects;

/**
 * A picture of an entity: a photo of a person, or a logo of a company or organisation.
 *
 * <p>Sieve stores where the picture is published, never the picture itself. Whoever shows it must
 * show the credit and licence with it.
 *
 * @param url the full-size picture
 * @param thumbnailUrl a small version of the picture, {@code null} when the publisher offers none
 * @param pageUrl the page that publishes the picture with its terms, such as a wanted poster or a
 *     media file page, may be {@code null}
 * @param credit who to credit, such as the photographer or the publishing agency, may be {@code
 *     null}
 * @param licence the licence the picture is published under, such as "CC BY-SA 4.0" or "Public
 *     domain", {@code null} when the publisher states none
 */
public record EntityImage(
        String url, String thumbnailUrl, String pageUrl, String credit, String licence) {

    /**
     * Compact constructor with validation; blank optional values become {@code null}.
     *
     * @throws NullPointerException if {@code url} is {@code null}
     * @throws IllegalArgumentException if {@code url} is blank
     */
    public EntityImage {
        Objects.requireNonNull(url, "url must not be null");
        if (url.isBlank()) {
            throw new IllegalArgumentException("url must not be blank");
        }
        thumbnailUrl = blankToNull(thumbnailUrl);
        pageUrl = blankToNull(pageUrl);
        credit = blankToNull(credit);
        licence = blankToNull(licence);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
