interface UseGoogleDrivePickerOptions {
  multiple?: boolean;
  mimeTypes?: string;
}

interface UseGoogleDrivePickerReturn {
  isEnabled: boolean;
  isLoading: boolean;
  error: string | null;
  clearError: () => void;
  openPicker: (options?: UseGoogleDrivePickerOptions) => Promise<File[]>;
}

export function useGoogleDrivePicker(): UseGoogleDrivePickerReturn {
  return {
    isEnabled: false,
    isLoading: false,
    error: null,
    clearError: () => {},
    openPicker: async () => [],
  };
}
