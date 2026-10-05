add_executable(CalendarAgendaTest
  CalendarAgendaTest.cpp
  ${REPO_ROOT}/lib/CalendarAgenda/CalendarAgenda.cpp
)

target_include_directories(CalendarAgendaTest PRIVATE
  ${REPO_ROOT}/lib/CalendarAgenda
)

target_link_libraries(CalendarAgendaTest PRIVATE
  crosspoint_test_common
  GTest::gtest_main
)

gtest_discover_tests(CalendarAgendaTest)
