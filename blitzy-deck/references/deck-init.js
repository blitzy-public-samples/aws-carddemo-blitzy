/*
 * deck-init.js -- CardDemo executive deck bootstrap.
 *
 * Externalized from the inline <script> in index.html so the deck's
 * Content-Security-Policy can restrict script-src to 'self' + the jsDelivr
 * origin WITHOUT allowing 'unsafe-inline' or 'unsafe-eval'. Loaded as a classic
 * script AFTER reveal.js, mermaid and lucide, so their globals are available.
 *
 * Mermaid keeps securityLevel:'loose' only because every diagram in this deck is
 * static, author-authored text (no user input is ever rendered). Even so, the
 * page CSP forbids inline/eval script execution, so any HTML a diagram could emit
 * cannot run script. Mermaid itself is pinned to a patched release (>= 11.15.0).
 */
  /* =====================================================================
     Init: Mermaid (manual render), Reveal, and Lucide icons.
     Mermaid uses startOnLoad:false and is rendered per-slide so diagrams on
     initially-hidden slides are measured while visible (avoids the tiny-SVG
     bug). themeVariables mirror the Blitzy dark palette declared in the theme.
     ===================================================================== */
  mermaid.initialize({
    startOnLoad: false,
    theme: 'base',
    securityLevel: 'loose',
    /* Force SVG <text> labels globally (Mermaid 11 honors the top-level flag). */
    htmlLabels: false,
    themeVariables: {
      background: '#0A0A0F',
      primaryColor: '#1C1930',
      primaryBorderColor: '#7C5CFC',
      primaryTextColor: '#F4F4FB',
      secondaryColor: '#141221',
      tertiaryColor: '#141221',
      lineColor: '#9D7BFF',
      clusterBkg: '#141221',
      clusterBorder: '#2A2740',
      fontFamily: 'Inter, system-ui, sans-serif'
    },
    /* htmlLabels:false renders node/edge labels as SVG <text> sized by the
       browser's actual glyph metrics, avoiding the foreignObject clipping that
       htmlLabels:true exhibits when the applied font is wider than Mermaid's
       internal measurement. All labels here are single-line plain text. */
    flowchart: { useMaxWidth: true, htmlLabels: false, curve: 'basis' }
  });

  /* =====================================================================
     Responsive mode selection.
     reveal.js scales a fixed 1280x720 canvas to fit the viewport. On a narrow
     / portrait screen (e.g. a 375x812 phone) that scale collapses the slide
     into an unreadable band. reveal.js 5's SUPPORTED scroll view reflows the
     deck into a vertically scrollable, viewport-width page instead of scaling,
     which keeps text, tables, diagrams and controls readable and operable.
     We enable the scroll view only for portrait / narrow viewports so the
     desktop presentation experience is unchanged. Because the view mode is
     fixed at initialization, we reload once when the viewport crosses the
     portrait<->landscape boundary; hash:true preserves the current slide so
     the reload is seamless.
     ===================================================================== */
  const PORTRAIT_MQ = window.matchMedia('(max-aspect-ratio: 4/5), (max-width: 700px)');
  const useScrollView = PORTRAIT_MQ.matches;

  /* Reload (preserving the slide via the URL hash) when the portrait/landscape
     boundary is crossed, so the correct view mode is applied. Guarded so it
     only fires on an actual change of match state. */
  let lastMatch = PORTRAIT_MQ.matches;
  function onOrientationBoundary(e) {
    if (e.matches !== lastMatch) {
      lastMatch = e.matches;
      window.location.reload();
    }
  }
  if (typeof PORTRAIT_MQ.addEventListener === 'function') {
    PORTRAIT_MQ.addEventListener('change', onOrientationBoundary);
  } else if (typeof PORTRAIT_MQ.addListener === 'function') {
    /* Legacy Safari fallback. */
    PORTRAIT_MQ.addListener(onOrientationBoundary);
  }

  /* Give decorative Lucide icons no accessible name and remove them from the
     tab order. Every icon in this deck is paired with a visible text label, so
     the icons are purely decorative; naming them would only add noise for
     screen-reader and keyboard users. Meaningful graphics (the Mermaid
     architecture diagrams) are named separately in nameMermaid(). */
  function hideDecorativeIcons() {
    document.querySelectorAll('.reveal svg.lucide').forEach((svg) => {
      svg.setAttribute('aria-hidden', 'true');
      svg.setAttribute('focusable', 'false');
    });
  }

  /* Resolve any <i data-lucide> placeholders into inline SVGs, then hide them. */
  function refreshIcons() {
    if (window.lucide && typeof lucide.createIcons === 'function') {
      lucide.createIcons();
    }
    hideDecorativeIcons();
  }

  /* Give each rendered Mermaid diagram an accessible name (role="img" +
     aria-label) taken from its slide's visible diagram title, so the
     architecture diagrams are announced meaningfully rather than exposing a
     wall of raw SVG <text> nodes. */
  function nameMermaid(scope) {
    (scope || document).querySelectorAll('.mermaid svg').forEach((svg) => {
      const slide = svg.closest('section');
      const title = slide ? slide.querySelector('.diagram-title') : null;
      const legend = slide ? slide.querySelector('.diagram-legend') : null;
      const name = title ? title.textContent.trim() : 'Architecture diagram';
      svg.setAttribute('role', 'img');
      svg.setAttribute('aria-label', legend
        ? name + '. ' + legend.textContent.trim()
        : name);
    });
  }

  /* Render Mermaid diagrams within a given slide (normal view), then re-fit. */
  async function renderMermaidIn(slide) {
    if (!slide) return;
    const nodes = slide.querySelectorAll('.mermaid:not([data-processed])');
    if (nodes.length) {
      try {
        await mermaid.run({ nodes: [...nodes] });
      } catch (err) {
        console.error('[deck] Mermaid render error:', err);
      }
      nameMermaid(slide);
      if (window.Reveal) Reveal.layout();
    }
  }

  /* Render every Mermaid diagram at once (scroll view lays all slides out, so
     they are all measurable and must all be drawn up front). */
  async function renderAllMermaid() {
    const nodes = document.querySelectorAll('.mermaid:not([data-processed])');
    if (nodes.length) {
      try {
        await mermaid.run({ nodes: [...nodes] });
      } catch (err) {
        console.error('[deck] Mermaid render error:', err);
      }
      nameMermaid(document);
      if (window.Reveal) Reveal.layout();
    }
  }

  /* Give reveal.js's generated chrome accessible names (the progress bar is an
     unlabeled interactive region by default). Called once after init. */
  function labelChrome() {
    const progress = document.querySelector('.reveal .progress');
    if (progress) {
      progress.setAttribute('role', 'progressbar');
      progress.setAttribute('aria-label', 'Presentation progress');
    }
  }

  const revealConfig = {
    width: 1280,
    height: 720,
    margin: 0.04,
    hash: true,
    controls: true,
    progress: true,
    center: true,
    slideNumber: 'c/t',
    transition: 'slide'
  };
  /* Portrait / narrow viewport: use the supported responsive scroll view. */
  if (useScrollView) {
    revealConfig.view = 'scroll';
  }

  Reveal.initialize(revealConfig).then(() => {
    refreshIcons();
    labelChrome();
    if (useScrollView) {
      renderAllMermaid();
    } else {
      renderMermaidIn(Reveal.getCurrentSlide());
    }
  });

  /* Render diagrams + re-resolve icons whenever a new slide is shown. */
  Reveal.on('slidechanged', (event) => {
    renderMermaidIn(event.currentSlide);
    refreshIcons();
  });

  /* Also resolve static icons as soon as the DOM is ready. */
  document.addEventListener('DOMContentLoaded', refreshIcons);
