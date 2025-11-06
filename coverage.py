#!/usr/bin/env python3
"""
Code Coverage Summary Tool
Displays JaCoCo test coverage in a readable format
"""

import xml.etree.ElementTree as ET
import sys
import os

def main():
    report_file = 'build/reports/jacoco/test/jacocoTestReport.xml'

    if not os.path.exists(report_file):
        print("❌ Coverage report not found!")
        print("Run: ./gradlew test")
        sys.exit(1)

    try:
        tree = ET.parse(report_file)
        root = tree.getroot()
    except Exception as e:
        print(f"❌ Error parsing coverage report: {e}")
        sys.exit(1)

    print("=" * 80)
    print("                        CODE COVERAGE SUMMARY")
    print("=" * 80)

    # Overall coverage
    for counter in root.findall('./counter'):
        ctype = counter.get('type')
        covered = int(counter.get('covered'))
        missed = int(counter.get('missed'))
        total = covered + missed
        percentage = (covered / total * 100) if total > 0 else 0

        bar_length = 40
        covered_bars = int((covered / total) * bar_length) if total > 0 else 0
        bar = '█' * covered_bars + '░' * (bar_length - covered_bars)

        print(f"\n{ctype:15} | {bar} | {percentage:6.2f}% ({covered}/{total})")

    print("\n" + "=" * 80)
    print("                       COVERAGE BY PACKAGE")
    print("=" * 80)

    for package in root.findall('./package'):
        pkg_name = package.get('name').replace('/', '.')

        line_counter = package.find("./counter[@type='LINE']")
        if line_counter is not None:
            covered = int(line_counter.get('covered'))
            missed = int(line_counter.get('missed'))
            total = covered + missed
            percentage = (covered / total * 100) if total > 0 else 0

            bar_length = 30
            covered_bars = int((percentage / 100) * bar_length)
            bar = '█' * covered_bars + '░' * (bar_length - covered_bars)

            print(f"\n{pkg_name:50} | {bar} | {percentage:6.2f}%")

    print("\n" + "=" * 80)
    print("                    LINE COVERAGE BY GROOVY FILE")
    print("=" * 80)

    # Collect Groovy file coverage data
    groovy_files = []
    for package in root.findall('./package'):
        pkg_name = package.get('name').replace('/', '.')
        for sourcefile in package.findall('./sourcefile'):
            file_name = sourcefile.get('name')
            # Only include .groovy files
            if file_name.endswith('.groovy'):
                line_counter = sourcefile.find("./counter[@type='LINE']")
                if line_counter is not None:
                    covered = int(line_counter.get('covered'))
                    missed = int(line_counter.get('missed'))
                    total = covered + missed
                    if total > 0:
                        percentage = (covered / total * 100)
                        full_path = f"{pkg_name}.{file_name}"
                        groovy_files.append((full_path, percentage, covered, total))

    # Sort by coverage percentage (descending)
    groovy_files.sort(key=lambda x: x[1], reverse=True)

    print(f"\n{'Rank':<5} {'File Name':<70} {'Lines':<15} {'Coverage':<10}")
    print("-" * 105)

    total_covered = 0
    total_lines = 0

    for i, (file_path, percentage, covered, total) in enumerate(groovy_files, 1):
        total_covered += covered
        total_lines += total
        
        bar_length = 20
        covered_bars = int((percentage / 100) * bar_length)
        bar = '█' * covered_bars + '░' * (bar_length - covered_bars)

        # Truncate long file paths but keep them readable
        display_name = file_path[-65:] if len(file_path) > 65 else file_path
        lines_text = f"{covered}/{total}"
        
        print(f"{i:<5} {display_name:<70} {lines_text:<15} {bar} {percentage:5.2f}%")

    # Calculate and display average
    average_percentage = (total_covered / total_lines * 100) if total_lines > 0 else 0
    
    print("-" * 105)
    print(f"{'':76} {'TOTAL':<15} {total_covered}/{total_lines} lines")
    print(f"{'':76} {'AVERAGE':<15} {average_percentage:5.2f}%")

    print("\n" + "=" * 80)
    print("                       ALL CLASSES BY COVERAGE")
    print("=" * 80)

    # Collect class coverage data
    classes_data = []
    for package in root.findall('./package'):
        pkg_name = package.get('name').replace('/', '.')
        for sourcefile in package.findall('./sourcefile'):
            class_name = sourcefile.get('name')
            line_counter = sourcefile.find("./counter[@type='LINE']")
            if line_counter is not None:
                covered = int(line_counter.get('covered'))
                missed = int(line_counter.get('missed'))
                total = covered + missed
                if total > 0:
                    percentage = (covered / total * 100)
                    classes_data.append((f"{pkg_name}.{class_name}", percentage, covered, total))

    # Sort by coverage percentage (descending)
    classes_data.sort(key=lambda x: x[1], reverse=True)

    print(f"{'Rank':<4} {'Class Name':<65} {'Coverage':<22} {'Percentage':<10}")
    print("-" * 105)

    total_class_coverage = 0
    num_classes = 0

    for i, (class_name, percentage, covered, total) in enumerate(classes_data, 1):
        bar_length = 20
        covered_bars = int((percentage / 100) * bar_length)
        bar = '█' * covered_bars + '░' * (bar_length - covered_bars)

        # Truncate long class names but keep them readable
        display_name = class_name[-60:] if len(class_name) > 60 else class_name
        coverage_text = f"({covered}/{total})"
        
        print(f"{i:<4} {display_name:<65} {bar} {percentage:6.2f}%")
        
        total_class_coverage += percentage
        num_classes += 1

    # Calculate and display class coverage average
    average_class_coverage = (total_class_coverage / num_classes) if num_classes > 0 else 0
    
    print("-" * 105)
    print(f"{'':70} {'TOTAL CLASSES':<22} {num_classes}")
    print(f"{'':70} {'AVERAGE':<22} {average_class_coverage:6.2f}%")

    print("\n" + "=" * 80)
    print("\n📊 Full HTML Report: build/reports/jacoco/test/html/index.html")
    print("📄 XML Report:       build/reports/jacoco/test/jacocoTestReport.xml")
    print("\n💡 To open HTML report: xdg-open build/reports/jacoco/test/html/index.html")
    print("\n" + "=" * 80)

if __name__ == '__main__':
    main()
