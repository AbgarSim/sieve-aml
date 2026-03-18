# Configuration file for the Sphinx documentation builder.
# https://www.sphinx-doc.org/en/master/usage/configuration.html

project = "Sieve AML"
copyright = "2026, Sieve Contributors"
author = "Sieve Contributors"
release = "0.1.0"

# -- General configuration ---------------------------------------------------

extensions = [
    "myst_parser",
    "sphinxcontrib.mermaid",
    "sphinx_copybutton",
    "sphinx_design",
    "breathe",
]

templates_path = ["_templates"]
exclude_patterns = ["_build", ".venv", "Thumbs.db", ".DS_Store"]

# MyST Markdown support
myst_enable_extensions = [
    "colon_fence",
    "deflist",
    "fieldlist",
    "tasklist",
]
source_suffix = {
    ".rst": "restructuredtext",
    ".md": "markdown",
}

# -- Options for HTML output -------------------------------------------------

html_theme = "furo"
html_title = "Sieve AML"
html_static_path = ["_static"]

html_theme_options = {
    "light_logo": "sieve-logo-light.svg",
    "dark_logo": "sieve-logo-dark.svg",
    "sidebar_hide_name": False,
    "navigation_with_keys": True,
    "footer_icons": [
        {
            "name": "GitHub",
            "url": "https://github.com/your-org/sieve-aml",
            "html": '<svg stroke="currentColor" fill="currentColor" stroke-width="0" viewBox="0 0 16 16"><path fill-rule="evenodd" d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"></path></svg>',
            "class": "",
        },
    ],
}

# -- Breathe configuration (Doxygen integration) ----------------------------

breathe_projects = {"sieve": "../doxygen/xml"}
breathe_default_project = "sieve"

# -- Mermaid configuration ---------------------------------------------------

mermaid_version = "10.6.1"
