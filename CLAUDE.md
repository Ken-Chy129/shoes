# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a full-stack application for sneaker price tracking and management across multiple platforms (KickScrew, StockX, Poison/得物). The project consists of:

- **Backend**: Spring Boot 3.4.1 application (Java 21) with MySQL database
- **Frontend**: React 18 + Ant Design Pro + UmiJS 4 admin console

## Architecture

### Backend Structure (`src/main/java/cn/ken/shoes/`)
- **Controllers**: REST API endpoints in `controller/` package
- **Services**: Business logic layer with interface-implementation pattern
- **Mappers**: MyBatis Plus database access layer with XML mapping files
- **Clients**: External API integrations (`KickScrewClient`, `PoisonClient`, `StockXClient`)
- **Models**: Request/Response DTOs, entities, and data objects
- **Scheduler**: Background tasks for price tracking (`PriceScheduler`)
- **Utils**: Common utilities for HTTP, JWT, proxy handling, etc.

### Frontend Structure (`console/`)
- **Pages**: Route-based page components in `src/pages/`
- **Components**: Reusable UI components in `src/components/`
- **Services**: API client functions in `src/services/`
- **Models**: TypeScript type definitions in `src/models/`

### Key Dependencies
- **Backend**: Spring Boot, MyBatis Plus, MySQL, Hutool, Guava, OkHttp3, JWT, EasyExcel
- **Frontend**: Ant Design Pro, UmiJS Max, React 18, TypeScript

## Development Commands

### Backend (Java/Spring Boot)
```bash
# Build the project
mvn clean compile

# Run tests
mvn test

# Package application
mvn clean package

# Run Spring Boot application
mvn spring-boot:run
```

### Frontend (React/UmiJS)
```bash
# Navigate to frontend directory
cd console

# Install dependencies
npm install

# Development server
npm run dev
# or
npm run start:dev

# Build for production
npm run build

# Lint code
npm run lint

# Type checking
npm run tsc

# Run tests
npm test

# Preview production build
npm run preview
```

## Database Configuration

The application uses MySQL with MyBatis Plus. Database entities are in `model/entity/` and corresponding mappers are in `src/main/resources/mapper/`.

## Authentication

- JWT-based authentication system
- Token verification aspect (`TokenAspect`) for protected endpoints
- Frontend stores tokens in sessionStorage

## Task System

- Annotation-based task execution (`@Task` annotation)
- Background task scheduling with Spring's `@EnableScheduling`
- Custom thread pool configuration for task execution

## External Integrations

The application integrates with multiple sneaker platforms:
- **KickScrew**: Product search and pricing via Algolia API
- **StockX**: Market data and pricing information  
- **Poison/得物**: Chinese sneaker platform integration

## File Structure Notes

- Configuration files are in `config/` packages
- Excel import/export models are in `model/excel/`
- HTTP client utilities support proxy configuration
- Aspect-oriented programming for cross-cutting concerns (auth, tasks)