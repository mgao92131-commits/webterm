if [[ -f "$HOME/.zshrc" ]]; then
  source "$HOME/.zshrc"
fi

if [[ -n "$WEBTERM_CWD" && -d "$WEBTERM_CWD" ]]; then
  cd "$WEBTERM_CWD"
fi

if [[ -o interactive ]]; then
  export CLICOLOR=1
  export LSCOLORS="${LSCOLORS:-ExGxBxDxCxEgEdxbxgxcxd}"
  alias ls='ls -G'
  autoload -Uz colors && colors
  PROMPT='%F{green}%n@%m%f %F{blue}%1~%f %# '
fi
