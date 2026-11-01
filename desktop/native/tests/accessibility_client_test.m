// In-process Cocoa accessibility client over hidden SDL windows; no synthetic OS input or Scala callbacks.
#import "../editor_ax.m"
#include <assert.h>

static id<NSAccessibility> find_title(id<NSAccessibility> object, NSString *title, unsigned depth) {
  assert(depth < 16);
  if([object.accessibilityTitle isEqualToString:title])return object;
  for(id<NSAccessibility> child in object.accessibilityChildren) {
    id<NSAccessibility> found = find_title(child, title, depth + 1);
    if(found)return found;
  }
  return nil;
}

static void expect_action(void *raw, int64_t revision, int64_t id, int64_t kind) {
  int64_t facts[5];
  char value[65537];
  assert(sirin_ax_poll(raw, facts, value, sizeof(value)) == 1);
  assert(facts[0] == revision && facts[1] == id && facts[2] == kind);
  assert(sirin_ax_poll(raw, facts, value, sizeof(value)) == 0);
}

static void view_focus(SirinAccessKit *owner, bool focused) {
  struct accesskit_macos_queued_events *events =
      accesskit_macos_subclassing_adapter_update_view_focus_state(owner.adapter, focused);
  if(events)accesskit_macos_queued_events_raise(events);
}

static void expect_empty(void *raw) {
  int64_t facts[5];
  char value[65537];
  assert(sirin_ax_poll(raw, facts, value, sizeof(value)) == 0);
}

int main(void) {
  @autoreleasepool {
    assert(SDL_Init(SDL_INIT_VIDEO));
    SDL_Window *window = SDL_CreateWindow("Hidden accessibility client", 300, 200, SDL_WINDOW_HIDDEN);
    assert(window);
    void *raw = sirin_ax_open(window, SDL_RegisterEvents(1));
    assert(raw);
    SirinAccessKit *owner = (__bridge SirinAccessKit *)raw;
    double fieldBounds[] = {10, 10, 200, 30};
    double buttonBounds[] = {10, 60, 100, 30};
    double spans[] = {
      0, 1, 10, 10, 10, 30, 0,
      1, 2, 20, 10, 10, 30, 0,
      2, 3, 30, 10, 10, 30, 0,
      3, 4, 40, 10, 10, 30, 0,
      4, 5, 50, 10, 10, 30, 0
    };
    assert(sirin_ax_begin(raw, 101) == 0);
    assert(sirin_ax_field(raw, 1, 0, "Title", "draft", "", 0, 0, 0, 1, fieldBounds, spans, 5) == 0);
    assert(sirin_ax_link(raw, 1, 0, 1) == 0);
    assert(sirin_ax_node(raw, 2, 0, 2, "Save", "", "", 1 | 2, -1, buttonBounds) == 0);
    assert(sirin_ax_publish(raw) == 0);
    id<NSAccessibility> root = (id<NSAccessibility>)owner.window.contentView;
    id<NSAccessibility> title = find_title(root, @"Title", 0);
    id<NSAccessibility> save = find_title(root, @"Save", 0);
    assert(title && save);
    assert([title.accessibilityRole isEqualToString:NSAccessibilityTextFieldRole]);
    assert([save.accessibilityRole isEqualToString:NSAccessibilityButtonRole]);
    assert([title isAccessibilitySelectorAllowed:@selector(setAccessibilityFocused:)]);
    assert(![title isAccessibilitySelectorAllowed:@selector(accessibilityPerformPress)]);
    assert([save isAccessibilitySelectorAllowed:@selector(accessibilityPerformPress)]);
    assert(![title accessibilityPerformPress]);
    expect_empty(raw);
    title.accessibilityFocused = YES;
    expect_action(raw, 101, 1, 1);
    assert(!title.accessibilityFocused);
    assert([save accessibilityPerformPress]);
    expect_action(raw, 101, 2, 4);
    puts("Cocoa client: text-field focus and button press queue exact semantic actions");

    // Hidden windows never receive OS focus. Exercise the adapter's view-focus input explicitly.
    assert(!owner.window.isKeyWindow);
    assert(sirin_ax_link(raw, 1, 0, 1 | 8) == 0);
    assert(sirin_ax_publish(raw) == 0);
    view_focus(owner, true);
    assert(title.accessibilityFocused);
    view_focus(owner, false);
    assert(!title.accessibilityFocused);
    puts("Cocoa client: focus becomes visible only after publication and active-view state");

    // Forward relationships must work through the platform objects, with no direct text override.
    assert(sirin_ax_begin(raw, 102) == 0);
    assert(sirin_ax_field(raw, 1, 0, "", "draft", "", 0, 0, 1, 1, fieldBounds, spans, 5) == 0);
    assert(sirin_ax_link(raw, 1, 0, 1 | 8) == 0);
    int64_t relationships[] = {3, 4, 0, 0, 0};
    assert(sirin_ax_relationships(raw, 1, relationships) == 0);
    assert(sirin_ax_node(raw, 3, 0, 5, "Linked title", "", "", 0, -1, buttonBounds) == 0);
    assert(sirin_ax_node(raw, 4, 0, 5, "Linked help", "", "", 0, -1, buttonBounds) == 0);
    assert(sirin_ax_node(raw, 5, 0, 3, "Remember", "", "", 1 | 2, 1, buttonBounds) == 0);
    assert(sirin_ax_publish(raw) == 0);
    title = find_title(root, @"Linked title", 0);
    assert(title && [title.accessibilityRole isEqualToString:NSAccessibilityTextFieldRole]);
    assert([title.accessibilityHelp isEqualToString:@"Linked help"]);
    id<NSAccessibility> checkbox = find_title(root, @"Remember", 0);
    assert(checkbox && [checkbox.accessibilityRole isEqualToString:NSAccessibilityCheckBoxRole]);
    assert([checkbox.accessibilityValue intValue] == 1);
    assert([checkbox accessibilityPerformPress]);
    expect_action(raw, 102, 5, 4);
    assert(![save accessibilityPerformPress]);
    save.accessibilityFocused = YES;
    expect_empty(raw);
    puts("Cocoa client: linked title/help and checked state resolve; retired objects cannot dispatch");

    for(int revision = 103; revision <= 105; revision++) {
      const char *explicitHelp = revision == 103 ? "Explicit help" : "";
      assert(sirin_ax_begin(raw, revision) == 0);
      assert(sirin_ax_field(raw, 1, 0, "Title", "draft", explicitHelp,
          0, 0, 0, 1, fieldBounds, spans, 5) == 0);
      assert(sirin_ax_link(raw, 1, 0, 1) == 0);
      if(revision != 105) {
        int64_t description[] = {0, 4, 0, 0, 0};
        assert(sirin_ax_relationships(raw, 1, description) == 0);
        assert(sirin_ax_node(raw, 4, 0, 5, "Fallback name", "Value help", "", 0, -1, buttonBounds) == 0);
      }
      assert(sirin_ax_node(raw, 5, 0, 3, "Remember", "", "", 1 | 2 | 4, 0, buttonBounds) == 0);
      assert(sirin_ax_publish(raw) == 0);
      title = find_title(root, @"Title", 0);
      assert(title);
      if(revision == 103)assert([title.accessibilityHelp isEqualToString:@"Explicit help"]);
      else if(revision == 104)assert([title.accessibilityHelp isEqualToString:@"Value help"]);
      else assert(title.accessibilityHelp.length == 0);
      assert(!checkbox.accessibilityEnabled);
      assert([checkbox.accessibilityValue intValue] == 0);
      [checkbox accessibilityPerformPress];
      checkbox.accessibilityFocused = YES;
      expect_empty(raw);
    }
    puts("Cocoa client: explicit help wins, Label values resolve, removed help clears and disabled actions ignored");

    assert(sirin_ax_begin(raw, 106) == 0);
    assert(sirin_ax_node(raw, 8, 0, 15, "Diagram", "", "", 0, -1, fieldBounds) == 0);
    assert(sirin_ax_node(raw, 6, 8, 16, "First part", "", "", 1 | 8, -1, fieldBounds) == 0);
    assert(sirin_ax_node(raw, 7, 8, 16, "Second part", "", "", 1, -1, buttonBounds) == 0);
    assert(sirin_ax_publish(raw) == 0);
    id<NSAccessibility> first = find_title(root, @"First part", 0);
    id<NSAccessibility> second = find_title(root, @"Second part", 0);
    assert(first && second);
    view_focus(owner, true);
    assert(first.accessibilityFocused && !second.accessibilityFocused);
    assert([second isAccessibilitySelectorAllowed:@selector(setAccessibilityFocused:)]);
    second.accessibilityFocused = YES;
    expect_action(raw, 106, 7, 1);
    assert(first.accessibilityFocused && !second.accessibilityFocused);
    assert(sirin_ax_link(raw, 6, 8, 1) == 0);
    assert(sirin_ax_link(raw, 7, 8, 1 | 8) == 0);
    assert(sirin_ax_publish(raw) == 0);
    // Publication refreshes actual key-window state; this test window remains hidden.
    view_focus(owner, true);
    assert(!first.accessibilityFocused && second.accessibilityFocused);
    expect_empty(raw);
    puts("Cocoa client: non-first graphic-part focus queues its exact identity and waits for owner publication");

    sirin_ax_close(raw);
    title.accessibilityFocused = YES;
    assert(![checkbox accessibilityPerformPress]);
    SDL_DestroyWindow(window);
    SDL_Quit();
    return 0;
  }
}
