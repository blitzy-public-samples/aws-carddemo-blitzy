import React, { memo, useEffect, ReactNode, FC, Fragment } from 'react';
import { createPortal } from 'react-dom';
import { Dialog, Transition } from '@headlessui/react';
import { X } from 'lucide-react';
import clsx from 'clsx';

/**
 * Size options for the modal dialog
 */
type ModalSize = 'sm' | 'md' | 'lg' | 'xl';

/**
 * Props for the Modal component
 * 
 * @interface ModalProps
 * @property {boolean} isOpen - Controls whether the modal is visible
 * @property {() => void} onClose - Callback function invoked when modal should close
 * @property {ReactNode} [title] - Optional title displayed in modal header
 * @property {ReactNode} children - Main content displayed in modal body
 * @property {ReactNode} [footer] - Optional footer content with action buttons
 * @property {ModalSize} [size='md'] - Size variant: 'sm' (384px), 'md' (512px), 'lg' (640px), 'xl' (768px)
 * @property {boolean} [closeOnOverlayClick=true] - Whether clicking backdrop closes the modal
 * @property {boolean} [closeOnEsc=true] - Whether pressing ESC key closes the modal
 * @property {boolean} [showCloseButton=true] - Whether to display X close button in header
 * @property {string} [className] - Additional CSS classes for modal panel customization
 */
export interface ModalProps {
  isOpen: boolean;
  onClose: () => void;
  title?: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  size?: ModalSize;
  closeOnOverlayClick?: boolean;
  closeOnEsc?: boolean;
  showCloseButton?: boolean;
  className?: string;
}

/**
 * Size mapping for modal width classes
 */
const sizeClasses: Record<ModalSize, string> = {
  sm: 'max-w-sm',
  md: 'max-w-md',
  lg: 'max-w-lg',
  xl: 'max-w-xl',
};

/**
 * Modal Dialog Component
 * 
 * Implements an accessible modal dialog with overlay backdrop following WCAG 2.1 AA guidelines.
 * Uses Headless UI Dialog component for built-in accessibility features including:
 * - Focus trap to prevent tab navigation outside modal
 * - Automatic focus management
 * - ARIA attributes (aria-modal, role="dialog")
 * - Keyboard ESC handling
 * 
 * Features:
 * - Portal rendering to document.body for proper z-index stacking
 * - Customizable header, body, and footer sections
 * - Multiple size variants (sm, md, lg, xl)
 * - Overlay backdrop with click-to-close functionality
 * - Close button with X icon in top-right corner
 * - Smooth enter/exit transitions with backdrop blur
 * - Prevents background scroll when modal is open
 * - Responsive design with mobile-first approach
 * 
 * @example
 * ```tsx
 * <Modal
 *   isOpen={isModalOpen}
 *   onClose={() => setIsModalOpen(false)}
 *   title="Confirm Action"
 *   size="md"
 *   footer={
 *     <>
 *       <Button onClick={handleConfirm}>Confirm</Button>
 *       <Button variant="secondary" onClick={() => setIsModalOpen(false)}>Cancel</Button>
 *     </>
 *   }
 * >
 *   <p>Are you sure you want to proceed with this action?</p>
 * </Modal>
 * ```
 * 
 * @param {ModalProps} props - Component props
 * @returns {React.ReactElement | null} Modal component rendered via portal
 */
const ModalComponent: FC<ModalProps> = ({
  isOpen,
  onClose,
  title,
  children,
  footer,
  size = 'md',
  closeOnOverlayClick = true,
  closeOnEsc = true,
  showCloseButton = true,
  className,
}) => {
  /**
   * Prevent body scroll when modal is open
   * Adds overflow-hidden class to document.body to prevent background scrolling
   * while modal is displayed, improving user experience and focus
   */
  useEffect(() => {
    if (isOpen) {
      // Save original overflow value
      const originalOverflow = document.body.style.overflow;
      
      // Prevent scrolling
      document.body.style.overflow = 'hidden';
      
      // Restore original overflow when modal closes
      return () => {
        document.body.style.overflow = originalOverflow;
      };
    }
  }, [isOpen]);

  /**
   * Handle overlay click
   * Only closes modal if closeOnOverlayClick is enabled
   */
  const handleOverlayClick = () => {
    if (closeOnOverlayClick) {
      onClose();
    }
  };

  /**
   * Handle ESC key press
   * Headless UI Dialog automatically calls onClose when ESC is pressed
   * This prop controls whether that behavior is enabled
   */
  const handleClose = () => {
    if (closeOnEsc) {
      onClose();
    }
  };

  /**
   * Render modal using React portal to document.body
   * This ensures proper z-index stacking and prevents CSS conflicts
   */
  const modalContent = (
    <Transition appear show={isOpen} as={Fragment}>
      <Dialog
        as="div"
        className="relative z-50"
        onClose={handleClose}
        aria-modal="true"
      >
        {/* Backdrop overlay with blur effect */}
        <Transition.Child
          as={Fragment}
          enter="ease-out duration-300"
          enterFrom="opacity-0"
          enterTo="opacity-100"
          leave="ease-in duration-200"
          leaveFrom="opacity-100"
          leaveTo="opacity-0"
        >
          <div
            className="fixed inset-0 bg-black/50 backdrop-blur-sm"
            onClick={handleOverlayClick}
            aria-hidden="true"
          />
        </Transition.Child>

        {/* Modal container - centered on screen */}
        <div className="fixed inset-0 overflow-y-auto">
          <div className="flex min-h-full items-center justify-center p-4 text-center sm:p-6 md:p-8">
            <Transition.Child
              as={Fragment}
              enter="ease-out duration-300"
              enterFrom="opacity-0 scale-95"
              enterTo="opacity-100 scale-100"
              leave="ease-in duration-200"
              leaveFrom="opacity-100 scale-100"
              leaveTo="opacity-0 scale-95"
            >
              <Dialog.Panel
                className={clsx(
                  'w-full transform overflow-hidden rounded-lg bg-white text-left align-middle shadow-xl transition-all',
                  sizeClasses[size],
                  className
                )}
              >
                {/* Header section */}
                {(title || showCloseButton) && (
                  <div className="relative border-b border-gray-200 px-6 py-4">
                    {/* Title */}
                    {title && (
                      <Dialog.Title
                        as="h3"
                        className="text-lg font-semibold leading-6 text-gray-900 pr-8"
                      >
                        {title}
                      </Dialog.Title>
                    )}
                    
                    {/* Close button */}
                    {showCloseButton && (
                      <button
                        type="button"
                        className="absolute right-4 top-4 rounded-md text-gray-400 hover:text-gray-500 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 transition-colors"
                        onClick={onClose}
                        aria-label="Close modal"
                      >
                        <X className="h-5 w-5" aria-hidden="true" />
                      </button>
                    )}
                  </div>
                )}

                {/* Body section */}
                <div className="px-6 py-4">
                  {children}
                </div>

                {/* Footer section */}
                {footer && (
                  <div className="border-t border-gray-200 px-6 py-4 bg-gray-50 flex items-center justify-end gap-3">
                    {footer}
                  </div>
                )}
              </Dialog.Panel>
            </Transition.Child>
          </div>
        </div>
      </Dialog>
    </Transition>
  );

  // Only render portal if we're in the browser environment
  if (typeof window === 'undefined') {
    return null;
  }

  // Render modal via portal to document.body
  return createPortal(modalContent, document.body);
};

/**
 * Memoized Modal component for performance optimization
 * Prevents unnecessary re-renders when parent components update
 * but modal props remain unchanged
 */
export const Modal = memo(ModalComponent);

// Set display name for debugging
Modal.displayName = 'Modal';
