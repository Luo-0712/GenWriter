import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Check, ChevronDown } from 'lucide-react';

const findNextEnabled = (options, start, direction) => {
  if (!options.length) return -1;
  for (let step = 1; step <= options.length; step += 1) {
    const index = (start + direction * step + options.length) % options.length;
    if (!options[index]?.disabled) return index;
  }
  return -1;
};

/** Native-select semantics with a themed, viewport-aware option surface. */
export default function SelectField({
  id,
  value = '',
  options = [],
  onChange,
  disabled = false,
  className = '',
  'aria-label': ariaLabel,
  'aria-labelledby': ariaLabelledby,
}) {
  const listId = useId();
  const rootRef = useRef(null);
  const triggerRef = useRef(null);
  const menuRef = useRef(null);
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);
  const [position, setPosition] = useState(null);
  const selectedIndex = options.findIndex((option) => String(option.value) === String(value));
  const selected = selectedIndex >= 0 ? options[selectedIndex] : null;

  useLayoutEffect(() => {
    if (!open) return undefined;
    const updatePosition = () => {
      const rect = triggerRef.current?.getBoundingClientRect();
      if (!rect) return;
      const menuHeight = menuRef.current?.offsetHeight || 0;
      const spaceBelow = window.innerHeight - rect.bottom - 12;
      const spaceAbove = rect.top - 12;
      const dropUp = spaceBelow < Math.min(menuHeight, 260) && spaceAbove > spaceBelow;
      const maxHeight = Math.max(100, Math.min(280, dropUp ? spaceAbove : spaceBelow));
      const width = Math.max(rect.width, Math.min(320, window.innerWidth - 24));
      return {
        left: Math.max(12, Math.min(rect.left, window.innerWidth - width - 12)),
        top: dropUp ? Math.max(12, rect.top - Math.min(menuHeight, maxHeight) - 6) : rect.bottom + 6,
        width,
        maxHeight,
        dropUp,
      };
    };
    setPosition(updatePosition());
    const frame = window.requestAnimationFrame(() => setPosition(updatePosition()));
    const handlePointer = (event) => {
      if (!rootRef.current?.contains(event.target) && !menuRef.current?.contains(event.target)) setOpen(false);
    };
    const handleFocus = (event) => {
      if (!rootRef.current?.contains(event.target) && !menuRef.current?.contains(event.target)) setOpen(false);
    };
    document.addEventListener('pointerdown', handlePointer, true);
    document.addEventListener('focusin', handleFocus);
    window.addEventListener('resize', updatePosition);
    window.addEventListener('scroll', updatePosition, true);
    return () => {
      window.cancelAnimationFrame(frame);
      document.removeEventListener('pointerdown', handlePointer, true);
      document.removeEventListener('focusin', handleFocus);
      window.removeEventListener('resize', updatePosition);
      window.removeEventListener('scroll', updatePosition, true);
    };
  }, [open]);

  useEffect(() => {
    if (!open) return;
    menuRef.current?.querySelector('[aria-selected="true"]')?.scrollIntoView({ block: 'nearest' });
  }, [open, activeIndex]);

  if (!options.length) return null;

  const openMenu = (direction = 0) => {
    if (disabled) return;
    const start = direction === 0 ? selectedIndex : direction > 0 ? -1 : options.length;
    setActiveIndex(findNextEnabled(options, start, direction || 1));
    setOpen(true);
  };
  const moveActive = (index) => {
    if (index >= 0 && !options[index]?.disabled) setActiveIndex(index);
  };
  const choose = (index) => {
    const option = options[index];
    if (!option || option.disabled) return;
    onChange(option.value, option);
    setOpen(false);
    triggerRef.current?.focus();
  };
  const handleTriggerKey = (event) => {
    if (disabled) return;
    if (['ArrowDown', 'ArrowUp', 'Enter', ' '].includes(event.key)) {
      event.preventDefault();
      if (!open) openMenu(event.key === 'ArrowUp' ? -1 : 1);
      else if (event.key === 'ArrowDown') moveActive(findNextEnabled(options, activeIndex, 1));
      else if (event.key === 'ArrowUp') moveActive(findNextEnabled(options, activeIndex, -1));
      else choose(activeIndex);
    } else if (open && ['Home', 'End'].includes(event.key)) {
      event.preventDefault();
      moveActive(findNextEnabled(options, event.key === 'Home' ? -1 : options.length, event.key === 'Home' ? 1 : -1));
    } else if (open && event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      setOpen(false);
    } else if (open && event.key === 'Tab') setOpen(false);
  };

  return (
    <div ref={rootRef} className={`select-field${open ? ' is-open' : ''}${className ? ` ${className}` : ''}`}>
      <button
        ref={triggerRef}
        id={id}
        type="button"
        role="combobox"
        className="select-field-trigger"
        disabled={disabled}
        aria-label={ariaLabel}
        aria-labelledby={ariaLabelledby}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? listId : undefined}
        onClick={() => open ? setOpen(false) : openMenu()}
        onKeyDown={handleTriggerKey}
      >
        <span className={selected ? 'select-field-value' : 'select-field-value is-placeholder'}>{selected?.label || '请选择'}</span>
        <ChevronDown size={15} aria-hidden="true" />
      </button>
      {open && position && createPortal(
        <div ref={menuRef} className={`select-field-menu${position.dropUp ? ' drop-up' : ''}`} style={{ left: position.left, top: position.top, width: position.width, maxHeight: position.maxHeight }}>
          <ul id={listId} role="listbox" aria-label={ariaLabel} aria-labelledby={ariaLabelledby} aria-activedescendant={activeIndex >= 0 ? `${listId}-${activeIndex}` : undefined}>
            {options.map((option, index) => (
              <li
                id={`${listId}-${index}`}
                key={option.value}
                role="option"
                aria-selected={index === selectedIndex}
                aria-disabled={option.disabled || undefined}
                className={`select-field-option${index === activeIndex ? ' is-active' : ''}${index === selectedIndex ? ' is-selected' : ''}`}
                onPointerMove={() => moveActive(index)}
                onPointerDown={(event) => event.preventDefault()}
                onClick={() => choose(index)}
              >
                <span>{option.label}</span>
                {index === selectedIndex && <Check size={15} aria-hidden="true" />}
              </li>
            ))}
          </ul>
        </div>,
        document.body,
      )}
    </div>
  );
}
