export type ProviderType = "oauth2" | "saml2" | "telegram" | "googledrive";

/** Value of a provider field, keyed by its `type`: text/password/textarea -> string,
 *  number -> number, switch -> boolean, tags -> string[]. */
export type FieldValue = string | number | boolean | string[];

export interface ProviderField {
  key: string;
  type: "text" | "password" | "switch" | "textarea" | "number" | "tags";
  label: string;
  description: string;
  placeholder?: string;
  defaultValue?: FieldValue;
}

/** Signing in with this provider, versus wiring the server to another service. */
export type ProviderCategory = "signin" | "integration";

export interface Provider {
  id: string;
  /** Which settings page lists it; defaults to "signin" when unset. */
  category?: ProviderCategory;
  name: string;
  /** A registry icon name, or a URL to an image (ProviderCard branches on it). */
  icon: string;
  type: ProviderType;
  scope: string; // Summary of what this provider does
  businessTier?: boolean; // Enterprise only
  documentationUrl?: string; // Optional link to documentation
  fields: ProviderField[];
}

export const useAllProviders = (): Provider[] => [];
