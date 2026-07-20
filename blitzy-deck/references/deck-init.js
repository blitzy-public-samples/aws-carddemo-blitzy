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

  /* Render only the diagrams on the given slide, then re-fit the layout. */
  async function renderMermaidIn(slide) {
    if (!slide) return;
    const nodes = slide.querySelectorAll('.mermaid:not([data-processed])');
    if (nodes.length) {
      try {
        await mermaid.run({ nodes: [...nodes] });
      } catch (err) {
        console.error('[deck] Mermaid render error:', err);
      }
      if (window.Reveal) Reveal.layout();
    }
  }

  /* Resolve any <i data-lucide> placeholders into inline SVGs. */
  function refreshIcons() {
    if (window.lucide && typeof lucide.createIcons === 'function') {
      lucide.createIcons();
    }
  }

  Reveal.initialize({
    width: 1280,
    height: 720,
    margin: 0.04,
    hash: true,
    controls: true,
    progress: true,
    center: true,
    slideNumber: 'c/t',
    transition: 'slide'
  }).then(() => {
    refreshIcons();
    renderMermaidIn(Reveal.getCurrentSlide());
  });

  /* Render diagrams + re-resolve icons whenever a new slide is shown. */
  Reveal.on('slidechanged', (event) => {
    renderMermaidIn(event.currentSlide);
    refreshIcons();
  });

  /* Also resolve static icons as soon as the DOM is ready. */
  document.addEventListener('DOMContentLoaded', refreshIcons);
