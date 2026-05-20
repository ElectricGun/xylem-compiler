# v0.0.0-alpha
- Initial release

# v0.1.0-alpha
- New Feature: Reference variables
    - Declared using `p<int>`, `p<double>`, `p<boolean>`
    - Dereferenced using `r()`
    - Example:
      ```
      -- allocate memory to the variable "test"
      p<int> test = 100;
      -- fetch the variable value
      print(r(test));
      printflush(@message1);
      -- prints out 100
      ```
- Changed template call syntax to be the same as function calls
- Changed macro literals from `$ $` to `${ }`
- Fixed exception when having binary operators on function returns
- Fixed empty void function throwing an error
- Changed std function `as_int` to `dtoi` for brevity

# v0.1.1-alpha
- Changed `p` datatype to `ptr`

# v0.1.2-alpha (contributed by @BnDLett)
Bug fixes:
- Added the Gradle wrapper jar, which is necessary for compiling the compiler.

Additions:
- A library for controlling blocks. Note that this library is incomplete and only contains enough macros to sense information from a block.
- Example code — both revolving around the control library and other features.
  - Fibonacci sequence
  - Sensing @enabled from a switch.
  - Getting the pixel position of a cursor relative to a display.

# v0.1.3-alpha
Bug fixes:
- Fixed boolean variables throwing a java exception when converted to mlog

# v0.1.4-alpha (contributed by @BnDLett)
- Updated the standard library. [Changelog](std/CHANGELOG.md#v010-contributed-by-bndlett)
- Added a [new test](tests/radar.mily)

# v0.1.5-alpha
- Refactored scope generation