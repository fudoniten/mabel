{
  description = "Mabel Home Bot";

  inputs = {
    nixpkgs.url = "nixpkgs/nixos-23.11";
    utils.url = "github:numtide/flake-utils";
    helpers = {
      url = "git+https://git.fudo.org/fudo-public/nix-helpers.git";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    mebot = {
      url = "git+https://fudo.dev/public/mebot.git";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, utils, helpers, mebot, ... }:
    utils.lib.eachDefaultSystem (system:
      let
        inherit (helpers.packages."${system}") mkClojureBin;
        pkgs = nixpkgs.legacyPackages."${system}";
        cljLibs = { "org.fudo/mebot" = "${mebot.packages."${system}".mebot}"; };
      in {
        packages = rec {
          default = mabel;
          mabel = mkClojureBin {
            name = "org.fudo/mabel";
            primaryNamespaces = "mebot.cli";
            src = ./.;
            inherit cljLibs;
          };
        };

        devShells = rec {
          default = updateDeps;
          updateDeps = pkgs.mkShell {
            buildInputs = with helpers.packages."${system}"; [ updateCljDeps ];
          };
          mabel = pkgs.mkShell {
            buildInputs = with self.packages."${system}"; [ mabel ];
          };
        };
      }) // {
        nixosModules = rec {
          default = mabel;
          mabel = import ./module.nix self.packages;
        };
      };
}
