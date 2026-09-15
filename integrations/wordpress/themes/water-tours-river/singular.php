<?php
/**
 * One page or one post.
 *
 * Why this file exists at all: before it, this theme shipped `front-page.php` and `index.php`, and
 * `index.php` rendered the landing page. So a WordPress page answered 200 with a full copy of the
 * commercial landing page under its own URL, core canonicalised that copy to itself, and
 * `wp-sitemap.xml` asked search engines to index it. The previous cycle stopped the indexing by
 * marking those responses `noindex` - correct as far as it went, but it left the site in a state
 * where the sitemap listed URLs the same site told crawlers not to index, and where creating an
 * ordinary page produced a duplicate rather than a page.
 *
 * `singular.php` sits below both `page.php` and `single.php` in the template hierarchy, so this
 * one file covers pages and posts. The `noindex` rule in `inc/seo.php` keys off the template
 * WordPress actually resolved, so nothing there needs editing: these views stop resolving to
 * `index.php` and stop being noindexed by themselves.
 *
 * It renders the title and the editor's content, and nothing else. No excerpt, no related posts,
 * no invented sections - the content is whatever the owner writes, and a template that pads it
 * out would be padding it with things nobody has confirmed.
 *
 * @package water-tours-river
 */

if (!defined('ABSPATH')) { exit; }

wt_river_chrome_header();

while (have_posts()) :
    the_post();
    ?>
    <article <?php post_class('inner-article'); ?>>
        <h1 class="inner-title"><?php the_title(); ?></h1>
        <div class="inner-content">
            <?php
            if (post_password_required()) {
                echo get_the_password_form();
            } else {
                the_content();
                wp_link_pages(array(
                    'before' => '<nav class="inner-pagination" aria-label="Страницы материала">',
                    'after'  => '</nav>',
                ));
            }
            ?>
        </div>
    </article>
    <?php
endwhile;

wt_river_chrome_footer();
